package com.developmentsprint.cryptocensus.it;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * Deterministic TLS probe workload; only locally generated synthetic certificate material is used.
 */
public final class TlsProbeWorkload {
  private TlsProbeWorkload() {}

  /** Runs successful, resumed, delegated-task and rejected-certificate paths. */
  public static void main(String[] args) throws Exception {
    run(Path.of(args[0]), args[1]);
    Files.write(Path.of(args[2]), TlsProbeBridge.snapshot());
  }

  /** Shared real-network workload for the feasibility and production collectors. */
  public static void run(Path store, String password) throws Exception {
    SSLContext trusted = context(store, password);
    socketConnections(trusted, trusted, 2, false);
    concurrentEngines(trusted);
    SSLContext untrusted = SSLContext.getInstance(protocol());
    untrusted.init(null, null, null);
    socketConnections(trusted, untrusted, 1, true);
  }

  private static String protocol() {
    return System.getProperty("census.tls.protocol", "TLSv1.3");
  }

  private static SSLContext context(Path store, String password) throws Exception {
    KeyStore keys = KeyStore.getInstance("PKCS12");
    try (InputStream input = Files.newInputStream(store)) {
      keys.load(input, password.toCharArray());
    }
    KeyManagerFactory keyManagers = KeyManagerFactory.getInstance("PKIX");
    keyManagers.init(keys, password.toCharArray());
    TrustManagerFactory trustManagers = TrustManagerFactory.getInstance("PKIX");
    trustManagers.init(keys);
    SSLContext context = SSLContext.getInstance(protocol());
    context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
    return context;
  }

  private static void socketConnections(
      SSLContext serverContext, SSLContext clientContext, int count, boolean failureExpected)
      throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (SSLServerSocket listener =
        (SSLServerSocket)
            serverContext
                .getServerSocketFactory()
                .createServerSocket(0, 10, java.net.InetAddress.getLoopbackAddress())) {
      listener.setEnabledProtocols(new String[] {protocol()});
      listener.setSoTimeout(10_000);
      Future<?> server =
          executor.submit(
              () -> {
                for (int i = 0; i < count; i++) {
                  try (SSLSocket socket = (SSLSocket) listener.accept()) {
                    socket.setSoTimeout(10_000);
                    socket.startHandshake();
                    if (failureExpected) {
                      throw new AssertionError("untrusted client unexpectedly completed");
                    }
                    socket.getOutputStream().write(7);
                    if (socket.getInputStream().read() != 9) {
                      throw new AssertionError("client application data missing");
                    }
                  } catch (SSLException expected) {
                    if (!failureExpected) {
                      throw new IllegalStateException(expected);
                    }
                  } catch (java.io.IOException error) {
                    throw new IllegalStateException(error);
                  }
                }
              });
      for (int i = 0; i < count; i++) {
        try (SSLSocket client =
            (SSLSocket)
                clientContext
                    .getSocketFactory()
                    .createSocket("localhost", listener.getLocalPort())) {
          client.setEnabledProtocols(new String[] {protocol()});
          client.setSoTimeout(10_000);
          client.startHandshake();
          if (failureExpected) {
            throw new AssertionError("untrusted server unexpectedly accepted");
          }
          if (client.getInputStream().read() != 7) {
            throw new AssertionError("server application data missing");
          }
          client.getOutputStream().write(9);
        } catch (SSLException expected) {
          if (!failureExpected) {
            throw expected;
          }
        }
      }
      server.get(30, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
  }

  private static void concurrentEngines(SSLContext context) throws Exception {
    ExecutorService connections = Executors.newFixedThreadPool(2);
    ExecutorService tasks =
        Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "tls-delegated-task"));
    CountDownLatch ready = new CountDownLatch(2);
    try {
      List<Future<?>> results = new java.util.ArrayList<>();
      for (int i = 0; i < 2; i++) {
        results.add(
            connections.submit(
                () -> {
                  try {
                    engineConnection(context, tasks, ready);
                  } catch (Exception error) {
                    throw new IllegalStateException(error);
                  }
                }));
      }
      for (Future<?> result : results) {
        result.get(30, TimeUnit.SECONDS);
      }
    } finally {
      connections.shutdownNow();
      tasks.shutdownNow();
    }
  }

  private static void engineConnection(
      SSLContext context, ExecutorService tasks, CountDownLatch ready) throws Exception {
    engineConnection(context, context, tasks, ready);
  }

  static void engineConnection(
      SSLContext serverContext,
      SSLContext clientContext,
      ExecutorService tasks,
      CountDownLatch ready)
      throws Exception {
    SSLEngine client = clientContext.createSSLEngine("localhost", 443);
    client.setUseClientMode(true);
    client.setEnabledProtocols(new String[] {protocol()});
    SSLEngine server = serverContext.createSSLEngine();
    server.setUseClientMode(false);
    server.setEnabledProtocols(new String[] {protocol()});
    final ByteBuffer toServer = ByteBuffer.allocate(65_536);
    final ByteBuffer toClient = ByteBuffer.allocate(65_536);
    final ByteBuffer application = ByteBuffer.allocate(65_536);
    final ByteBuffer empty = ByteBuffer.allocate(0);
    client.beginHandshake();
    server.beginHandshake();
    ready.countDown();
    if (!ready.await(10, TimeUnit.SECONDS)) {
      throw new AssertionError("concurrent engines did not start");
    }
    int delegatedTasks = 0;
    try {
      for (int turn = 0; turn < 1000; turn++) {
        delegatedTasks += delegated(client, tasks);
        delegatedTasks += delegated(server, tasks);
        if (client.getHandshakeStatus() == HandshakeStatus.NEED_WRAP) {
          client.wrap(empty, toServer);
        }
        if (server.getHandshakeStatus() == HandshakeStatus.NEED_WRAP) {
          server.wrap(empty, toClient);
        }
        unwrap(server, toServer, application);
        unwrap(client, toClient, application);
        if (client.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING
            && server.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING
            && toServer.position() == 0
            && toClient.position() == 0) {
          if (delegatedTasks == 0) {
            throw new AssertionError("engine fixture did not run delegated tasks");
          }
          return;
        }
      }
      throw new AssertionError(
          "engine handshake stalled: "
              + List.of(client.getHandshakeStatus(), server.getHandshakeStatus()));
    } finally {
      client.closeOutbound();
      server.closeOutbound();
    }
  }

  private static int delegated(SSLEngine engine, ExecutorService executor) throws Exception {
    int count = 0;
    Runnable task;
    while ((task = engine.getDelegatedTask()) != null) {
      executor.submit(task).get(10, TimeUnit.SECONDS);
      count++;
    }
    return count;
  }

  private static void unwrap(SSLEngine engine, ByteBuffer network, ByteBuffer application)
      throws SSLException {
    if (network.position() == 0 || engine.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
      return;
    }
    network.flip();
    application.clear();
    engine.unwrap(network, application);
    network.compact();
  }
}
