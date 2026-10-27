package com.developmentsprint.cryptocensus.delivery;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

/**
 * Narrow alternate transport for explicitly authenticated CONNECT, with no global state changes.
 */
public final class AuthenticatedProxyTransport {
  private AuthenticatedProxyTransport() {}

  /** Sanitized response; only Retry-After is exposed, never arbitrary response headers. */
  @SuppressWarnings("ArrayRecordComponent")
  public record Response(int statusCode, byte[] body, String retryAfter, String failureCode) {
    /** Copies the bounded response payload. */
    public Response {
      body = body.clone();
    }

    @Override
    public byte[] body() {
      return body.clone();
    }

    @Override
    public String toString() {
      return "Response[statusCode=" + statusCode + "]";
    }
  }

  /** Sends exactly one request; the timeout includes CONNECT, authentication, TLS and body. */
  @SuppressWarnings({"PMD.PreserveStackTrace", "PMD.CloseResource"})
  // Java17 executor has no close; finally cancels, closes the client immediately and shuts down.
  public static Response send(
      DeliveryOptions options, HttpRequest message, byte[] body, int maximum, Duration deadline) {
    AuthenticatedProxy proxy = options.authenticatedProxy();
    if (proxy == null || !"https".equals(message.uri().getScheme())) {
      throw new IllegalArgumentException("Authenticated proxy requires an HTTPS request");
    }
    if (!TransportLogging.safe(true)) {
      return failure("UNSAFE_TRANSPORT_LOGGING");
    }
    long started = System.nanoTime();
    HttpUriRequestBase request = new HttpUriRequestBase(message.method(), message.uri());
    message
        .headers()
        .map()
        .forEach((name, values) -> values.forEach(value -> request.addHeader(name, value)));
    if (body.length > 0) {
      request.setEntity(new ByteArrayEntity(body, (ContentType) null));
    }
    ExecutorService executor =
        Executors.newSingleThreadExecutor(
            task -> {
              Thread thread = new Thread(task, "census-proxy-delivery");
              thread.setDaemon(true);
              return thread;
            });
    Future<Response> future = null;
    CloseableHttpClient active = null;
    try {
      if (Thread.currentThread().isInterrupted()) {
        return failure("INTERRUPTED");
      }
      CloseableHttpClient client = client(options, deadline);
      active = client;
      if (deadline.toNanos() - (System.nanoTime() - started) <= 0) {
        return failure("DEADLINE_BEFORE_SEND");
      }
      future =
          executor.submit(
              () ->
                  client.execute(
                      request,
                      response -> {
                        byte[] bytes =
                            response.getEntity() == null
                                ? new byte[0]
                                : response.getEntity().getContent().readNBytes(maximum + 1);
                        if (bytes.length > maximum) {
                          request
                              .cancel(); // Do not let response cleanup drain an unbounded entity.
                          throw new BodyLimit();
                        }
                        var retry = response.getFirstHeader("Retry-After");
                        String value =
                            retry == null
                                ? null
                                : response.getHeaders("Retry-After").length != 1
                                        || retry.getValue().length() > 128
                                    ? "INVALID"
                                    : retry.getValue();
                        return new Response(response.getCode(), bytes, value, null);
                      }));
      long remaining = deadline.toNanos() - (System.nanoTime() - started);
      if (remaining <= 0) {
        return failure("TIMEOUT");
      }
      return future.get(remaining, TimeUnit.NANOSECONDS);
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
      request.cancel();
      if (active != null) {
        active.close(CloseMode.IMMEDIATE);
      }
      if (future != null) {
        future.cancel(true);
      }
      executor.shutdownNow();
    }
  }

  private static CloseableHttpClient client(DeliveryOptions options, Duration deadline) {
    AuthenticatedProxy proxy = options.authenticatedProxy();
    var tls = ClientTlsStrategyBuilder.create();
    if (options.sslContext() != null) {
      tls.setSslContext(options.sslContext());
    }
    Timeout timeout = Timeout.ofMilliseconds(Math.max(1, deadline.toMillis()));
    var manager =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setConnectionFactory(
                ManagedHttpClientConnectionFactory.builder()
                    .http1Config(
                        Http1Config.custom()
                            .setMaxLineLength(8192)
                            .setMaxHeaderCount(128)
                            .setMaxEmptyLineCount(8)
                            .build())
                    .build())
            .setTlsSocketStrategy(tls.buildClassic())
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(
                        Timeout.ofMilliseconds(
                            Math.min(
                                options.connectTimeout().toMillis(), timeout.toMilliseconds())))
                    .setSocketTimeout(timeout)
                    .build())
            .build();
    var credentials =
        new UsernamePasswordCredentials(
            proxy.credentials().username(), proxy.credentials().password());
    return HttpClients.custom()
        .setConnectionManager(manager)
        .setProxy(new HttpHost("http", proxy.host(), proxy.port()))
        .setDefaultCredentialsProvider(
            (scope, context) -> matchesProxy(proxy, scope) ? credentials : null)
        .setTargetAuthenticationStrategy((challengeType, challenges, context) -> List.of())
        .setDefaultRequestConfig(
            RequestConfig.custom()
                .setResponseTimeout(timeout)
                .setConnectionRequestTimeout(timeout)
                .setProxyPreferredAuthSchemes(List.of("Basic"))
                .build())
        .disableAutomaticRetries()
        .disableRedirectHandling()
        .disableCookieManagement()
        .disableContentCompression()
        .disableAuthCaching()
        .build();
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "Proxy hosts are validated ASCII and the Basic scheme is an ASCII protocol token")
  private static boolean matchesProxy(
      AuthenticatedProxy proxy, org.apache.hc.client5.http.auth.AuthScope scope) {
    return proxy.host().equalsIgnoreCase(scope.getHost())
        && proxy.port() == scope.getPort()
        && "Basic".equalsIgnoreCase(scope.getSchemeName());
  }

  private static Response failure(String reason) {
    return new Response(0, new byte[0], null, reason);
  }

  private static String reason(Throwable error) {
    Throwable current = error;
    for (int count = 0; count < 16 && current != null; count++, current = current.getCause()) {
      if (current instanceof BodyLimit) {
        return "RESPONSE_TOO_LARGE";
      }
      if (current instanceof javax.net.ssl.SSLException) {
        return "TLS_FAILURE";
      }
      if (current instanceof java.net.SocketTimeoutException) {
        return "TIMEOUT";
      }
    }
    return "TRANSPORT_FAILURE";
  }

  private static final class BodyLimit extends IOException {
    private static final long serialVersionUID = 1L;
  }
}
