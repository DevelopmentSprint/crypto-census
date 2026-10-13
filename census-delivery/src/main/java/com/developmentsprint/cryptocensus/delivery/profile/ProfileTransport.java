package com.developmentsprint.cryptocensus.delivery.profile;

import com.developmentsprint.cryptocensus.delivery.AuthenticatedProxyTransport;
import com.developmentsprint.cryptocensus.delivery.DeliveryOptions;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLParameters;

/**
 * Central transport: same-origin requests, explicit auth, no redirects and body-inclusive
 * deadlines.
 */
final class ProfileTransport {
  private final HttpClient client;
  private final DeliveryOptions options;
  private final long started;

  @SuppressWarnings(
      "PMD.CloseResource") // Java17 HttpClient has no close; body futures complete/cancel.
  ProfileTransport(DeliveryOptions options, long started) {
    this.options = options;
    this.started = started;
    if (options.authenticatedProxy() == null
        && Boolean.getBoolean("jdk.httpclient.enableAllMethodRetry")) {
      client = null;
      return;
    }
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
    client = builder.build();
  }

  ProtocolResponse send(
      URI base,
      AdapterRequest request,
      String secret,
      AdapterOptions.AuthenticationScheme scheme,
      int maximum) {
    if (client == null
        || (options.authenticatedProxy() == null
            && Boolean.getBoolean("jdk.httpclient.enableAllMethodRetry"))) {
      return failure("UNSAFE_AUTOMATIC_RETRY");
    }
    if (!com.developmentsprint.cryptocensus.delivery.TransportLogging.safe(
        options.authenticatedProxy() != null)) {
      return failure("UNSAFE_TRANSPORT_LOGGING");
    }
    CompletableFuture<HttpResponse<byte[]>> future = null;
    try {
      long remaining = remaining();
      if (remaining <= 0) {
        return failure("DEADLINE_BEFORE_SEND");
      }
      if (request.delayMillis() < 0 || request.delayMillis() > 1000) {
        throw new IllegalArgumentException("Invalid protocol delay");
      }
      if (request.delayMillis() > 0) {
        long delay = TimeUnit.MILLISECONDS.toNanos(request.delayMillis());
        if (delay >= remaining) {
          return failure("OPERATION_TIMEOUT");
        }
        TimeUnit.NANOSECONDS.sleep(delay);
        remaining = remaining();
      }
      if (remaining <= 0) {
        return failure("DEADLINE_BEFORE_SEND");
      }
      final URI endpoint = resolve(base, request.relativePath());
      if (!Set.of("GET", "POST", "PUT").contains(request.method())
          || request.mutation() == "GET".equals(request.method())) {
        throw new IllegalArgumentException("Invalid protocol method");
      }
      byte[] body = request.body();
      if (body.length > 16 * 1024 * 1024 + 16384) {
        throw new IllegalArgumentException("Protocol request too large");
      }
      if ("GET".equals(request.method()) && body.length != 0) {
        throw new IllegalArgumentException("Read-only request cannot carry a body");
      }
      long limit = Math.min(remaining, options.requestTimeout().toNanos());
      final long attempt = System.nanoTime();
      HttpRequest.Builder message =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofNanos(limit))
              .header("Accept", "application/json");
      if (request.contentType() != null) {
        if (!request.contentType().matches("[\\x20-\\x7e]{1,256}")) {
          throw new IllegalArgumentException("Invalid request content type");
        }
        message.header("Content-Type", request.contentType());
      }
      for (Map.Entry<String, String> header : request.headers().entrySet()) {
        if (!Set.of("X-Correlation-Id", "X-Tenant-Id", "Idempotency-Key").contains(header.getKey())
            || !header.getValue().matches("[\\x21-\\x7e]{1,256}")) {
          throw new IllegalArgumentException("Unsupported extension header");
        }
        message.header(header.getKey(), header.getValue());
      }
      if (secret != null) {
        if (secret.isEmpty() || secret.length() > 8192 || !secret.matches("[\\x21-\\x7e]+")) {
          throw new IllegalArgumentException("Invalid resolved authentication secret");
        }
        message.header(
            scheme == AdapterOptions.AuthenticationScheme.API_KEY ? "X-Api-Key" : "Authorization",
            switch (scheme) {
              case API_KEY -> secret;
              case BEARER -> "Bearer " + secret;
              case BASIC -> "Basic " + secret;
            });
      }
      message.method(
          request.method(),
          body.length == 0
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(body));
      if (options.authenticatedProxy() != null) {
        var response =
            AuthenticatedProxyTransport.send(
                options,
                message.build(),
                body,
                maximum,
                Duration.ofNanos(Math.min(limit, remaining())));
        return new ProtocolResponse(
            response.statusCode(), response.body(), response.failureCode(), response.retryAfter());
      }
      future = client.sendAsync(message.build(), ignored -> new Body(maximum));
      long wait = Math.min(limit - (System.nanoTime() - attempt), remaining());
      if (wait <= 0) {
        return failure("TIMEOUT");
      }
      HttpResponse<byte[]> response = future.get(wait, TimeUnit.NANOSECONDS);
      var advice = response.headers().allValues("Retry-After");
      String retryAfter =
          advice.isEmpty()
              ? null
              : advice.size() != 1 || advice.get(0).length() > 128 ? "INVALID" : advice.get(0);
      return new ProtocolResponse(response.statusCode(), response.body(), null, retryAfter);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return failure("INTERRUPTED");
    } catch (TimeoutException error) {
      return failure("TIMEOUT");
    } catch (ExecutionException error) {
      return failure(reason(error));
    } catch (RuntimeException error) {
      return failure("TRANSPORT_FAILURE");
    } finally {
      if (future != null && !future.isDone()) {
        future.cancel(true);
      }
    }
  }

  long remaining() {
    return options.overallTimeout().toNanos() - (System.nanoTime() - started);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "URI ASCII serialization preserves the validated endpoint; adapter paths are ASCII only")
  static URI resolve(URI base, String relative) {
    if (relative.isEmpty()) {
      return base;
    }
    if (relative.length() > 2048
        || !relative.matches("[A-Za-z0-9_./?=&-]+")
        || relative.startsWith("/")
        || relative.contains("..")) {
      throw new IllegalArgumentException("Unsafe adapter path");
    }
    URI root =
        URI.create(
            base.toASCIIString().endsWith("/") ? base.toASCIIString() : base.toASCIIString() + "/");
    URI resolved = root.resolve(relative);
    if (!root.getScheme().equals(resolved.getScheme())
        || !root.getAuthority().equals(resolved.getAuthority())
        || !resolved.getPath().startsWith(root.getPath())) {
      throw new IllegalArgumentException("Adapter changed destination origin or base path");
    }
    return resolved;
  }

  private static ProtocolResponse failure(String code) {
    return new ProtocolResponse(0, new byte[0], code);
  }

  private static String reason(Throwable error) {
    Throwable current = error;
    for (int i = 0; i < 16 && current != null; i++, current = current.getCause()) {
      if (current instanceof BodyLimit) {
        return "RESPONSE_TOO_LARGE";
      }
      if (current instanceof java.net.http.HttpTimeoutException) {
        return "TIMEOUT";
      }
      if (current instanceof javax.net.ssl.SSLHandshakeException) {
        return "TLS_FAILURE";
      }
    }
    return "TRANSPORT_FAILURE";
  }

  private static final class BodyLimit extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    BodyLimit() {
      super("Response exceeds its limit");
    }
  }

  private static final class Body implements HttpResponse.BodySubscriber<byte[]> {
    private final int maximum;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> completion = new CompletableFuture<>();
    private Flow.Subscription subscription;

    Body(int maximum) {
      this.maximum = maximum;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return completion.minimalCompletionStage();
    }

    @Override
    public void onSubscribe(Flow.Subscription incoming) {
      if (subscription != null) {
        incoming.cancel();
        return;
      }
      subscription = incoming;
      incoming.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      if (subscription == null) {
        completion.completeExceptionally(
            new IllegalStateException("Missing response subscription"));
        return;
      }
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > maximum - bytes.size()) {
          subscription.cancel();
          completion.completeExceptionally(new BodyLimit());
          return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable error) {
      completion.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
      completion.complete(bytes.toByteArray());
    }
  }
}
