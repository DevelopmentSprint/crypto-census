package com.developmentsprint.cryptocensus.delivery;

import com.developmentsprint.cryptocensus.core.Cbom;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;

/** One bounded delivery attempt, retaining the original evidence and an atomic safe receipt. */
public final class DeliveryEngine {
  private final DeliveryAdapter adapter = new GenericHttpsAdapter();

  /**
   * Validates exact input bytes and expected digest before sending. Network outcomes are receipts;
   * invalid inputs and receipt persistence failures throw sanitized exceptions without raw causes.
   * An UNKNOWN result must be reconciled with the receiver before repeating a POST.
   */
  @SuppressWarnings(
      "PMD.PreserveStackTrace") // Raw IO/transport causes may contain credentials or paths.
  @SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "URI ASCII serialization is the exact endpoint identity, not an authentication"
              + " normalization")
  public DeliveryReceipt deliver(DeliveryRequest request) throws IOException {
    if (Thread.currentThread().isInterrupted()) {
      throw new DeliveryException(
          "INTERRUPTED_BEFORE_SEND", "Delivery interrupted before any network attempt");
    }
    long started = System.nanoTime();
    byte[] bytes = validate(request);
    String digest = sha256(bytes);
    String endpointHash =
        sha256(request.endpoint().toASCIIString().getBytes(StandardCharsets.UTF_8));
    String identity =
        DeliveryIdentity.of(
            request.endpoint(),
            request.destinationId(),
            request.application(),
            request.module(),
            request.version(),
            request.runId(),
            digest);
    DeliveryReceipt pending =
        new DeliveryReceipt(
            DeliveryReceipt.Outcome.UNKNOWN, digest, identity, 0, "ATTEMPT_STARTED");
    // Persist intent before any network operation. A crash cannot leave a previous success receipt.
    try {
      ReceiptStore.write(request, pending, endpointHash, bytes.length);
    } catch (IOException | RuntimeException error) {
      throw new DeliveryException(
          "RECEIPT_INTENT_FAILED", "Cannot persist delivery intent; no delivery attempted");
    }
    DeliveryReceipt result = send(request, bytes, digest, identity, started);
    boolean interrupted = Thread.interrupted();
    try {
      ReceiptStore.write(request, result, endpointHash, bytes.length);
    } catch (IOException | RuntimeException error) {
      throw new ReceiptWriteException(result.successful());
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    return result;
  }

  @SuppressWarnings(
      "PMD.PreserveStackTrace") // Artifact parser causes may contain uploaded private data.
  private static byte[] validate(DeliveryRequest request) throws IOException {
    try {
      ReceiptStore.validatePaths(request);
    } catch (IOException | RuntimeException error) {
      throw new DeliveryException(
          "RECEIPT_PATH_INVALID", "Receipt must be a regular file distinct from the artifact");
    }
    byte[] bytes;
    try {
      bytes = Cbom.readBytes(request.artifact());
    } catch (IOException | RuntimeException error) {
      throw new DeliveryException(
          "ARTIFACT_READ_FAILED", "Artifact must be a readable regular file at most 16 MiB");
    }
    if (!sha256(bytes).equals(request.expectedSha256())) {
      throw new DeliveryException(
          "ARTIFACT_DIGEST_MISMATCH", "Artifact digest does not match expected SHA-256");
    }
    try {
      EvidenceValidator.validate(Cbom.parseEvidence(bytes));
    } catch (IllegalArgumentException error) {
      throw new DeliveryException(
          "INVALID_CBOM",
          "Artifact must be valid Crypto Census CBOM evidence with health metadata");
    }
    return bytes;
  }

  @SuppressWarnings(
      "PMD.CloseResource") // Java 17 HttpClient has no close(); all body futures complete or
  // cancel.
  private DeliveryReceipt send(
      DeliveryRequest request, byte[] bytes, String digest, String identity, long started) {
    if (request.options().authenticatedProxy() == null
        && Boolean.getBoolean("jdk.httpclient.enableAllMethodRetry")) {
      return failure(digest, identity, DeliveryReceipt.Outcome.FAILED, "UNSAFE_AUTOMATIC_RETRY");
    }
    if (!TransportLogging.safe(request.options().authenticatedProxy() != null)) {
      return failure(digest, identity, DeliveryReceipt.Outcome.FAILED, "UNSAFE_TRANSPORT_LOGGING");
    }
    CompletableFuture<HttpResponse<Void>> future = null;
    try {
      HttpClient client = client(request.options());
      long remaining = request.options().overallTimeout().toNanos() - (System.nanoTime() - started);
      if (remaining <= 0) {
        return failure(digest, identity, DeliveryReceipt.Outcome.FAILED, "DEADLINE_BEFORE_SEND");
      }
      Duration timeout =
          Duration.ofNanos(Math.min(remaining, request.options().requestTimeout().toNanos()));
      HttpRequest message = adapter.prepare(request, bytes, digest, identity, timeout);
      if (request.options().authenticatedProxy() != null) {
        var response =
            AuthenticatedProxyTransport.send(request.options(), message, bytes, 65536, timeout);
        return response.failureCode() == null
            ? adapter.interpret(response.statusCode(), digest, identity)
            : failure(
                digest,
                identity,
                "DEADLINE_BEFORE_SEND".equals(response.failureCode())
                    ? DeliveryReceipt.Outcome.FAILED
                    : DeliveryReceipt.Outcome.UNKNOWN,
                response.failureCode());
      }
      long attemptStarted = System.nanoTime();
      future = client.sendAsync(message, ignored -> new BoundedResponseSubscriber());
      // HttpRequest.timeout may finish at headers. Bound the future through the entire body too.
      long elapsed = System.nanoTime();
      long bodyRemaining =
          Math.min(
              timeout.toNanos() - (elapsed - attemptStarted),
              request.options().overallTimeout().toNanos() - (elapsed - started));
      if (bodyRemaining <= 0) {
        return failure(digest, identity, DeliveryReceipt.Outcome.UNKNOWN, "TIMEOUT");
      }
      HttpResponse<Void> response = future.get(bodyRemaining, TimeUnit.NANOSECONDS);
      return adapter.interpret(response.statusCode(), digest, identity);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return failure(digest, identity, DeliveryReceipt.Outcome.UNKNOWN, "INTERRUPTED");
    } catch (TimeoutException error) {
      return failure(digest, identity, DeliveryReceipt.Outcome.UNKNOWN, "TIMEOUT");
    } catch (ExecutionException error) {
      return failure(digest, identity, DeliveryReceipt.Outcome.UNKNOWN, reason(error));
    } catch (RuntimeException error) {
      return failure(digest, identity, DeliveryReceipt.Outcome.UNKNOWN, "TRANSPORT_FAILURE");
    } finally {
      if (future != null && !future.isDone()) {
        future.cancel(true);
      }
    }
  }

  private static HttpClient client(DeliveryOptions options) {
    SSLParameters tls = new SSLParameters();
    tls.setEndpointIdentificationAlgorithm("HTTPS");
    HttpClient.Builder builder =
        HttpClient.newBuilder()
            .connectTimeout(options.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .sslParameters(tls)
            .proxy(
                options.proxySelector() == null
                    ? ProxySelector.of((InetSocketAddress) null)
                    : options.proxySelector());
    if (options.sslContext() != null) {
      builder.sslContext(options.sslContext());
    }
    return builder.build();
  }

  private static String reason(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 16; depth++, current = current.getCause()) {
      if (current instanceof BoundedResponseSubscriber.ResponseTooLargeException) {
        return "RESPONSE_TOO_LARGE";
      }
      if (current instanceof HttpTimeoutException) {
        return "TIMEOUT";
      }
      if (current instanceof SSLHandshakeException) {
        return "TLS_FAILURE";
      }
    }
    return "TRANSPORT_FAILURE";
  }

  private static DeliveryReceipt failure(
      String digest, String identity, DeliveryReceipt.Outcome outcome, String reason) {
    return new DeliveryReceipt(outcome, digest, identity, 0, reason);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }
}
