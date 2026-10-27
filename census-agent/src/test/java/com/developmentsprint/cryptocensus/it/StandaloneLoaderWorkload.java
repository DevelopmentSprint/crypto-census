package com.developmentsprint.cryptocensus.it;

import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import com.developmentsprint.cryptocensus.annotations.DataProtection;
import java.io.IOException;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import javax.crypto.Cipher;

/** Real standalone application frames cross a declaring child loader and a nondeclaring caller. */
public final class StandaloneLoaderWorkload {
  private static final String DECLARED = StandaloneLoaderWorkload.class.getName() + "$Declared";

  private StandaloneLoaderWorkload() {}

  public static void main(String[] args) throws Exception {
    ChildJvmCoverage.dumpAfterAgentShutdown();
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    PublicKey key = generator.generateKeyPair().getPublic();
    int deployments = Integer.parseInt(args[0]);
    for (int index = 0; index < deployments; index++) {
      var loader = new DeclaringLoader("deployment-" + index);
      loader.loadClass(DECLARED).getMethod("run", PublicKey.class).invoke(null, key);
    }
  }

  public static final class Declared {
    private Declared() {}

    @CryptoFlow("archive")
    @DataProtection(classification = "restricted", confidentialityYears = 20)
    public static void run(PublicKey key) throws Exception {
      Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
      cipher.init(Cipher.ENCRYPT_MODE, key);
    }
  }

  private static final class DeclaringLoader extends ClassLoader {
    private DeclaringLoader(String name) {
      super(name, StandaloneLoaderWorkload.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!name.equals(DECLARED)) {
        return super.loadClass(name, resolve);
      }
      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
          try (var bytes = getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (bytes == null) {
              throw new ClassNotFoundException(name);
            }
            byte[] definition = bytes.readAllBytes();
            loaded =
                defineClass(
                    name,
                    definition,
                    0,
                    definition.length,
                    StandaloneLoaderWorkload.class.getProtectionDomain());
          } catch (IOException failure) {
            throw new ClassNotFoundException(name, failure);
          }
        }
        if (resolve) {
          resolveClass(loaded);
        }
        return loaded;
      }
    }
  }
}
