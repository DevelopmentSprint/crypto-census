package com.developmentsprint.cryptocensus.it;

import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Overlapping accepted/rejected engines share one worker and carry distinguishable crypto probes.
 */
public final class MixedTlsWorkload {
  private MixedTlsWorkload() {}

  public static void main(String[] args) throws Exception {
    ChildJvmCoverage.dumpAfterAgentShutdown();
    SSLContext trusted = context(Path.of(args[0]), args[1], true, "SHA3-256");
    SSLContext untrusted = context(Path.of(args[0]), args[1], false, "SHA3-512");
    ExecutorService connections = Executors.newFixedThreadPool(2);
    SharedTasks tasks = new SharedTasks();
    CountDownLatch ready = new CountDownLatch(2);
    try {
      var accepted =
          connections.submit(
              () -> {
                accept(trusted, tasks, ready);
                return true;
              });
      var rejected =
          connections.submit(
              () -> {
                reject(trusted, untrusted, tasks, ready);
                return true;
              });
      if (!accepted.get(20, TimeUnit.SECONDS)
          || !rejected.get(20, TimeUnit.SECONDS)
          || tasks.submitters.size() != 2
          || tasks.workers.size() != 1) {
        throw new AssertionError("Connections did not overlap on the same delegated worker");
      }
      Files.writeString(Path.of(args[2]), "accepted=true;rejected=true;submitters=2;workers=1");
    } finally {
      connections.shutdownNow();
      tasks.shutdownNow();
    }
  }

  @CryptoFlow("accepted")
  public static void accept(SSLContext context, ExecutorService tasks, CountDownLatch ready)
      throws Exception {
    TlsProbeWorkload.engineConnection(context, context, tasks, ready);
  }

  @CryptoFlow("rejected")
  public static void reject(
      SSLContext server, SSLContext client, ExecutorService tasks, CountDownLatch ready)
      throws Exception {
    try {
      TlsProbeWorkload.engineConnection(server, client, tasks, ready);
    } catch (SSLException expected) {
      return;
    }
    throw new AssertionError("Untrusted connection unexpectedly completed");
  }

  private static SSLContext context(Path store, String password, boolean trusted, String marker)
      throws Exception {
    KeyStore keys = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(store)) {
      keys.load(input, password.toCharArray());
    }
    KeyManagerFactory keyManagers = KeyManagerFactory.getInstance("PKIX");
    keyManagers.init(keys, password.toCharArray());
    TrustManagerFactory trustManagers = TrustManagerFactory.getInstance("PKIX");
    trustManagers.init(trusted ? keys : (KeyStore) null);
    X509TrustManager delegate = (X509TrustManager) trustManagers.getTrustManagers()[0];
    TrustManager trace =
        new X509TrustManager() {
          @Override
          public void checkClientTrusted(X509Certificate[] chain, String authType)
              throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
          }

          @Override
          public void checkServerTrusted(X509Certificate[] chain, String authType)
              throws CertificateException {
            try {
              // Synthetic marker operation executed inside the actual owned TLS delegated callback.
              MessageDigest.getInstance(marker).digest(new byte[16]);
            } catch (java.security.NoSuchAlgorithmException unavailable) {
              throw new CertificateException("Required fixture digest unavailable", unavailable);
            }
            delegate.checkServerTrusted(chain, authType);
          }

          @Override
          public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
          }
        };
    SSLContext context = SSLContext.getInstance("TLSv1.3");
    context.init(keyManagers.getKeyManagers(), new TrustManager[] {trace}, null);
    return context;
  }

  private static final class SharedTasks extends AbstractExecutorService {
    private final ExecutorService delegate = Executors.newSingleThreadExecutor();
    private final CountDownLatch firstSubmissions = new CountDownLatch(2);
    private final Set<Thread> submitters = ConcurrentHashMap.newKeySet();
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();

    @Override
    public void execute(Runnable command) {
      if (submitters.add(Thread.currentThread())) {
        firstSubmissions.countDown();
      }
      delegate.execute(
          () -> {
            try {
              if (!firstSubmissions.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError(
                    "Both handshakes must submit before either first task runs");
              }
              workers.add(Thread.currentThread());
              command.run();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new AssertionError(interrupted);
            }
          });
    }

    @Override
    public void shutdown() {
      delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
      return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
      return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return delegate.awaitTermination(timeout, unit);
    }
  }
}
