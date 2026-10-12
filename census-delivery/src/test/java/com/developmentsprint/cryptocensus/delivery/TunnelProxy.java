package com.developmentsprint.cryptocensus.delivery;

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
final class TunnelProxy implements AutoCloseable {
  private final ServerSocket listener;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final AtomicReference<String> request = new AtomicReference<>();
  private final AtomicReference<Socket> incoming = new AtomicReference<>();
  private final AtomicReference<Socket> outgoing = new AtomicReference<>();

  TunnelProxy(int targetPort) throws IOException {
    listener = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
    executor.execute(
        () -> {
          try {
            Socket client = listener.accept();
            incoming.set(client);
            client.setSoTimeout(5000);
            ByteArrayOutputStream headers = new ByteArrayOutputStream();
            while (headers.size() < 8192) {
              int value = client.getInputStream().read();
              if (value < 0) {
                throw new IOException("Incomplete CONNECT request");
              }
              headers.write(value);
              if (headers.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                break;
              }
            }
            request.set(headers.toString(StandardCharsets.US_ASCII));
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
            executor.execute(() -> forward(target, client));
            forward(client, target);
          } catch (IOException failure) {
            request.compareAndSet(null, "CONNECT_FAILED");
          }
        });
  }

  int port() {
    return listener.getLocalPort();
  }

  String connectRequest() {
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
