package com.developmentsprint.cryptocensus.delivery.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** A loopback-only CONNECT fixture; it never forwards to a request-selected destination. */
public final class TunnelProxy implements AutoCloseable {
  private final ServerSocket listener;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final AtomicReference<String> request = new AtomicReference<>();
  private final AtomicReference<Socket> incoming = new AtomicReference<>();
  private final AtomicReference<Socket> outgoing = new AtomicReference<>();

  /** Creates an unauthenticated tunnel to one fixed loopback port. */
  public TunnelProxy(int targetPort) throws IOException {
    this(targetPort, null);
  }

  @SuppressWarnings(
      "StringConcatToTextBlock") // HTTP requires explicit CRLF, not platform text blocks.
  public TunnelProxy(int targetPort, String expectedAuthorization) throws IOException {
    listener = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
    executor.execute(
        () -> {
          try {
            Socket client;
            while (true) {
              client = listener.accept();
              incoming.set(client);
              String headers = readHeaders(client);
              request.set(headers);
              if (expectedAuthorization == null
                  || headers
                      .toLowerCase(java.util.Locale.ROOT)
                      .contains(
                          "proxy-authorization: "
                              + expectedAuthorization.toLowerCase(java.util.Locale.ROOT))) {
                break;
              }
              client
                  .getOutputStream()
                  .write(
                      ("HTTP/1.1 407 Proxy Authentication Required\r\n"
                              + "Proxy-Authenticate: Basic realm=\"census-test\"\r\n"
                              + "Content-Length: 0\r\n"
                              + "Connection: close\r\n\r\n")
                          .getBytes(StandardCharsets.US_ASCII));
              client.close();
            }
            final Socket connected = client;
            incoming.set(client);
            Socket target = new Socket();
            target.connect(
                new InetSocketAddress(
                    InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), targetPort),
                5000);
            outgoing.set(target);
            client
                .getOutputStream()
                .write(
                    "HTTP/1.1 200 Connection established\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            executor.execute(() -> forward(target, connected));
            forward(client, target);
          } catch (IOException failure) {
            request.compareAndSet(null, "CONNECT_FAILED");
          }
        });
  }

  private static String readHeaders(Socket client) throws IOException {
    client.setSoTimeout(5000);
    ByteArrayOutputStream headers = new ByteArrayOutputStream();
    while (headers.size() < 8192) {
      int value = client.getInputStream().read();
      if (value < 0) {
        throw new IOException("Incomplete CONNECT request");
      }
      headers.write(value);
      if (headers.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
        return headers.toString(StandardCharsets.US_ASCII);
      }
    }
    throw new IOException("CONNECT headers too large");
  }

  /** Returns the allocated loopback proxy port. */
  public int port() {
    return listener.getLocalPort();
  }

  /** Returns the captured test-only CONNECT request. */
  public String connectRequest() {
    return request.get();
  }

  private static void forward(Socket source, Socket destination) {
    try {
      source.getInputStream().transferTo(destination.getOutputStream());
    } catch (IOException expectedOnShutdown) {
      // Closing either side deliberately tears down the test's tunnel.
    }
  }

  @Override
  public void close() throws IOException {
    listener.close();
    if (incoming.get() != null) {
      incoming.get().close();
    }
    if (outgoing.get() != null) {
      outgoing.get().close();
    }
    executor.shutdownNow();
  }
}
