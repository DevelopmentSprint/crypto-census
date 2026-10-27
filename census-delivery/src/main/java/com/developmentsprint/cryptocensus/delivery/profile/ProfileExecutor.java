package com.developmentsprint.cryptocensus.delivery.profile;

import com.developmentsprint.cryptocensus.delivery.DeliveryException;
import com.developmentsprint.cryptocensus.delivery.DeliveryOptions;
import com.developmentsprint.cryptocensus.delivery.ProxyCredentials;
import com.developmentsprint.cryptocensus.delivery.ReceiptWriteException;
import com.developmentsprint.cryptocensus.delivery.TransportConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Drives pure adapter sessions through one shared bounded transport and persists staged evidence.
 */
public final class ProfileExecutor {
  private final ProfilePlanner planner;

  /** Shares the planner's exact validation and adapter registry. */
  public ProfileExecutor(ProfilePlanner planner) {
    this.planner = planner;
  }

  /** Revalidates the plan, resolves secrets only now, and records every protocol observation. */
  public ProfileReceipt execute(PlannedDelivery proposed, SecretReference.Resolver resolver)
      throws IOException {
    return run(proposed, resolver, false);
  }

  /** Performs only configured retention readback and adapter read-only preflight requests. */
  public ProfileReceipt preflight(PlannedDelivery proposed, SecretReference.Resolver resolver)
      throws IOException {
    return run(proposed, resolver, true);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "URI.toASCIIString defines the exact endpoint identity; no text normalization is"
              + " intended")
  @SuppressWarnings("PMD.PreserveStackTrace")
  private ProfileReceipt run(
      PlannedDelivery proposed, SecretReference.Resolver resolver, boolean preflight)
      throws IOException {
    long monotonic = System.nanoTime();
    Instant started = Instant.now();
    PlannedDelivery plan = planner.revalidate(proposed);
    DeliveryProfile profile = plan.profile();
    try {
      ProfileReceiptStore.validate(plan);
    } catch (IOException | RuntimeException error) {
      throw DeliveryException.invalidReceiptPath();
    }
    final String secret = resolve(profile.authentication(), resolver);
    byte[] caBytes =
        profile.transport().caBundle() == null
            ? null
            : TransportConfiguration.readCaBundle(profile.transport().caBundle());
    String caIdentity = caBytes == null ? "JVM_DEFAULT_TRUST" : ProfileFiles.hash(caBytes);
    if (!ProfileFiles.sameHash(
        JsonSupport.string(plan.sanitizedPlan(), "caBundleSha256"), caIdentity)) {
      throw new IllegalArgumentException("Custom CA bundle changed since planning");
    }
    DeliveryOptions options =
        TransportConfiguration.optionsWithCaBytes(
            caBytes,
            profile.transport().proxyHost(),
            profile.transport().proxyPort(),
            profile.transport().proxyUsername() == null
                ? null
                : ProxyCredentials.basic(
                    resolve(profile.transport().proxyUsername(), resolver),
                    resolve(profile.transport().proxyPassword(), resolver)));
    ProfileTransport transport = new ProfileTransport(options, monotonic);
    AdapterResult result =
        new AdapterResult(
            AdapterResult.Upload.NOT_ATTEMPTED,
            AdapterResult.Ingestion.UNVERIFIED,
            "PREPARED",
            0,
            null,
            null,
            List.of());
    VerifiedRetention retention = null;
    VerifiedRetention destinationReadback = null;
    int exchanges = 0;
    persist(plan, result, null, null, started, 0, false);
    if (profile.retention() != null) {
      String evidenceSecret = resolve(profile.retention().authentication(), resolver);
      ProtocolResponse evidence =
          transport.send(
              profile.retention().endpoint(),
              new AdapterRequest("GET", "", null, new byte[0], false, 0),
              evidenceSecret,
              profile.retention().authentication().basic()
                  ? AdapterOptions.AuthenticationScheme.BASIC
                  : AdapterOptions.AuthenticationScheme.BEARER,
              16 * 1024 * 1024);
      exchanges++;
      if (evidence.statusCode() != 200
          || evidence.failureCode() != null
          || !ProfileFiles.hash(evidence.body()).equals(plan.bundle().publication().sha256())) {
        result =
            new AdapterResult(
                AdapterResult.Upload.NOT_ATTEMPTED,
                AdapterResult.Ingestion.UNVERIFIED,
                "ORIGINAL_RETENTION_UNVERIFIED",
                evidence.statusCode(),
                null,
                null,
                List.of());
        return persist(plan, result, null, null, started, exchanges, false);
      }
      retention =
          new VerifiedRetention(
              plan.bundle().publication().sha256(),
              ProfileFiles.hash(
                  profile.retention().endpoint().toASCIIString().getBytes(StandardCharsets.UTF_8)),
              Instant.now());
    }
    byte[] publication = plan.bundle().publication().readVerified();
    AdapterSession session =
        profile
            .adapter()
            .open(
                new AdapterContext(
                    plan.bundle(),
                    profile.endpoint(),
                    profile.options(),
                    profile.policy(),
                    retention),
                publication);
    ProtocolStep step = session.first();
    boolean mutation = false;
    int mutations = 0;
    boolean mutationAcknowledged = false;
    while (step.request() != null) {
      result = step.observation();
      if (exchanges >= 64 || transport.remaining() <= 0) {
        result = stopped(result, "OPERATION_LIMIT", mutation);
        return persist(plan, result, retention, destinationReadback, started, exchanges, false);
      }
      AdapterRequest request = step.request();
      if (preflight && request.mutation()) {
        result =
            new AdapterResult(
                AdapterResult.Upload.NOT_ATTEMPTED,
                AdapterResult.Ingestion.UNVERIFIED,
                "REMOTE_PREFLIGHT_COMPLETE",
                0,
                null,
                result.serverVersion(),
                result.lossCodes());
        return persist(plan, result, retention, destinationReadback, started, exchanges, false);
      }
      if (request.mutation() && ++mutations > 1) {
        result = stopped(result, "MUTATION_LIMIT", mutation);
        return persist(plan, result, retention, destinationReadback, started, exchanges, false);
      }
      mutation |= request.mutation();
      if (request.mutation()) {
        destinationReadback = null;
        result = stopped(result, "ATTEMPT_STARTED", true);
        persist(plan, result, retention, null, started, exchanges, false);
      }
      GenericProfileAdapter.Options retry =
          profile.options() instanceof GenericProfileAdapter.Options generic
                  && generic.receiverSupportsIdempotency()
                  && request.mutation()
              ? generic
              : null;
      if (retry != null) {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>(request.headers());
        headers.put("Idempotency-Key", deliveryIdentity(plan));
        request =
            new AdapterRequest(
                request.method(),
                request.relativePath(),
                request.contentType(),
                request.body(),
                true,
                request.delayMillis(),
                headers);
      }
      ProtocolResponse response;
      int attempts = 0;
      boolean ambiguous = false;
      do {
        response =
            transport.send(
                profile.endpoint(),
                request,
                secret,
                profile.options().authenticationScheme(),
                request.publicationReadback() ? 16 * 1024 * 1024 : 65536);
        ambiguous |= response.statusCode() >= 500 || response.failureCode() != null;
        attempts++;
        exchanges++;
        if (retry == null || attempts >= retry.maxAttempts() || !RetryPolicy.eligible(response)) {
          break;
        }
        long delay = RetryPolicy.delayMillis(response.retryAfter(), attempts, Instant.now());
        if (delay < 0
            || java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(delay) >= transport.remaining()) {
          break;
        }
        // Preserve ambiguity before waiting; a crash must never leave an old success receipt.
        persist(
            plan,
            stopped(result, "RETRY_PENDING", true),
            retention,
            destinationReadback,
            started,
            exchanges,
            false);
        try {
          java.util.concurrent.TimeUnit.MILLISECONDS.sleep(delay);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          response = new ProtocolResponse(0, new byte[0], "INTERRUPTED");
          break;
        }
      } while (true);
      if (attempts > 1
          && ambiguous
          && !(response.statusCode() >= 200 && response.statusCode() < 300)) {
        response = new ProtocolResponse(0, new byte[0], "RETRY_OUTCOME_UNKNOWN");
      }
      if (request.mutation()) {
        mutationAcknowledged =
            response.failureCode() == null
                && response.statusCode() >= 200
                && response.statusCode() < 300;
      }
      if (request.publicationReadback()) {
        destinationReadback = null;
        if (response.failureCode() == null
            && response.statusCode() == 200
            && ProfileFiles.hash(response.body()).equals(plan.bundle().publication().sha256())) {
          destinationReadback =
              new VerifiedRetention(
                  plan.bundle().publication().sha256(),
                  ProfileFiles.hash(
                      ProfileTransport.resolve(profile.endpoint(), request.relativePath())
                          .toASCIIString()
                          .getBytes(StandardCharsets.UTF_8)),
                  Instant.now());
        } else {
          step = session.accept(response);
          result = step.observation();
          if (result.upload() == AdapterResult.Upload.ACCEPTED && !mutationAcknowledged) {
            result = unacknowledged(result, mutation);
          }
          result = stopped(result, "DESTINATION_READBACK_UNVERIFIED", mutation);
          return persist(plan, result, retention, null, started, exchanges, false);
        }
      }
      step = session.accept(response);
      result = step.observation();
      if (result.upload() == AdapterResult.Upload.ACCEPTED && !mutationAcknowledged) {
        result = unacknowledged(result, mutation);
        return persist(plan, result, retention, destinationReadback, started, exchanges, false);
      }
      persist(plan, result, retention, destinationReadback, started, exchanges, false);
      if (response.failureCode() != null) {
        result = stopped(result, response.failureCode(), mutation);
        return persist(plan, result, retention, destinationReadback, started, exchanges, false);
      }
    }
    result = step.observation();
    boolean accepted = result.upload() == AdapterResult.Upload.ACCEPTED;
    if (accepted && !mutationAcknowledged) {
      result = unacknowledged(result, mutation);
      return persist(plan, result, retention, destinationReadback, started, exchanges, false);
    }
    boolean completionSatisfied =
        profile.policy().completion() == DeliveryPolicy.Completion.UPLOAD_ACCEPTED
            || result.ingestion() == AdapterResult.Ingestion.COMPLETED;
    if (accepted
        && completionSatisfied
        && profile.adapter().requiresPublicationReadback()
        && destinationReadback == null) {
      result = stopped(result, "DESTINATION_READBACK_UNVERIFIED", mutation);
    }
    boolean success =
        accepted
            && (!profile.adapter().requiresPublicationReadback() || destinationReadback != null)
            && completionSatisfied;
    return persist(plan, result, retention, destinationReadback, started, exchanges, success);
  }

  private static AdapterResult unacknowledged(AdapterResult result, boolean mutation) {
    return new AdapterResult(
        mutation ? AdapterResult.Upload.UNKNOWN : AdapterResult.Upload.NOT_ATTEMPTED,
        AdapterResult.Ingestion.UNVERIFIED,
        "UPLOAD_ACCEPTANCE_UNVERIFIED",
        result.httpStatus(),
        result.remoteOperationId(),
        result.serverVersion(),
        result.lossCodes());
  }

  private static String deliveryIdentity(PlannedDelivery plan) {
    var mapping = plan.bundle().correlation();
    return com.developmentsprint.cryptocensus.delivery.DeliveryIdentity.of(
        plan.profile().endpoint(),
        plan.profile().destinationId(),
        mapping.application(),
        mapping.module(),
        mapping.version(),
        mapping.runId(),
        plan.bundle().publication().sha256(),
        ((GenericProfileAdapter.Options) plan.profile().options()).headers());
  }

  private static String resolve(SecretReference reference, SecretReference.Resolver resolver) {
    if (reference.kind() == SecretReference.Kind.NONE) {
      return null;
    }
    String value = resolver.resolve(reference);
    if (reference.basic()) {
      SecretReference.validateBasic(value);
    }
    if (value == null
        || value.isEmpty()
        || value.length() > 8192
        || !value.matches("[\\x21-\\x7e]+")) {
      throw new IllegalArgumentException("Referenced authentication secret is absent or invalid");
    }
    return value;
  }

  private static AdapterResult stopped(AdapterResult previous, String reason, boolean mutation) {
    return new AdapterResult(
        previous.upload() == AdapterResult.Upload.ACCEPTED
            ? AdapterResult.Upload.ACCEPTED
            : mutation ? AdapterResult.Upload.UNKNOWN : AdapterResult.Upload.NOT_ATTEMPTED,
        previous.ingestion(),
        reason,
        previous.httpStatus(),
        previous.remoteOperationId(),
        previous.serverVersion(),
        previous.lossCodes());
  }

  @SuppressWarnings("PMD.PreserveStackTrace")
  private static ProfileReceipt persist(
      PlannedDelivery plan,
      AdapterResult result,
      VerifiedRetention retention,
      VerifiedRetention destinationReadback,
      Instant started,
      int exchanges,
      boolean success)
      throws IOException {
    ProfileReceipt receipt =
        new ProfileReceipt(
            plan.planSha256(),
            result,
            retention,
            started,
            Instant.now(),
            exchanges,
            success,
            destinationReadback);
    boolean interrupted = Thread.interrupted();
    try {
      ProfileReceiptStore.write(plan, receipt);
    } catch (IOException | RuntimeException error) {
      throw ReceiptWriteException.afterDelivery(result.upload() == AdapterResult.Upload.ACCEPTED);
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    return receipt;
  }
}
