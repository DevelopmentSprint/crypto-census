package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.core.CryptoAsset;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Primitive;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyPairGeneratorSpi;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.AlgorithmParameterSpec;
import java.util.List;
import javax.crypto.KeyGenerator;
import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Recording a generator must not change how the JDK picks its provider. {@code getProvider()} on
 * {@code KeyGenerator} and on {@code KeyPairGenerator}'s failover delegate turns failover off, so a
 * provider failing after the first key would then fail the application instead of falling back.
 */
class GeneratorFailoverTest {

  private static final String ALGORITHM = "CensusFailoverTest";

  @BeforeAll
  static void installProviders() {
    // The flaky provider is preferred; the steady one is the fallback.
    Security.insertProviderAt(new TestProvider("CensusFlaky", Flaky.class.getName()), 1);
    Security.insertProviderAt(new TestProvider("CensusSteady", Steady.class.getName()), 2);
  }

  @AfterAll
  static void removeProviders() {
    Security.removeProvider("CensusFlaky");
    Security.removeProvider("CensusSteady");
  }

  @BeforeEach
  void freshInventory() {
    Recorder.configure(500, 50);
    Recorder.enable();
    Flaky.calls = 0;
    FlakyPairs.calls = 0;
  }

  @AfterEach
  void disable() {
    Recorder.disable();
  }

  @Test
  void recordingKeyGeneratorKeepsFailover() throws Exception {
    KeyGenerator generator = KeyGenerator.getInstance(ALGORITHM);
    SecretKey first = generator.generateKey(); // the flaky provider still works once
    Recorder.exit(Recorder.enter(), generator, first, null, null);

    assertThat(generator.generateKey().getAlgorithm()).isEqualTo("steady");
    assertThat(assets())
        .singleElement()
        .satisfies(a -> assertThat(a.primitive()).isEqualTo(Primitive.KEY_GENERATION));
  }

  @Test
  void recordingTheKeyPairGeneratorDelegateKeepsFailover() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
    assertThat(generator.getClass().getName()).isEqualTo(Recorder.KEY_PAIR_GENERATOR_DELEGATE);
    KeyPair first = generator.generateKeyPair();
    Recorder.exit(Recorder.enter(), generator, first, null, null);

    assertThat(generator.generateKeyPair().getPublic().getAlgorithm()).isEqualTo("steady");
    assertThat(assets()).singleElement().satisfies(a -> assertThat(a.provider()).isNull());
  }

  @Test
  void usesTheProviderTheAdvicePassesIn() throws Exception {
    KeyGenerator generator = KeyGenerator.getInstance(ALGORITHM);
    SecretKey key = generator.generateKey();
    Recorder.exit(Recorder.enter(), generator, key, Security.getProvider("CensusFlaky"), null);
    assertThat(assets())
        .singleElement()
        .satisfies(a -> assertThat(a.provider()).isEqualTo("CensusFlaky"));
  }

  private static List<CryptoAsset> assets() {
    return Recorder.inventory().entries().stream().map(Inventory.Entry::asset).toList();
  }

  // ---------------------------------------------------------------- test providers

  private static final class TestProvider extends Provider {
    private static final long serialVersionUID = 1L;

    TestProvider(String name, String keyGenerator) {
      super(name, "1.0", "Crypto Census failover test provider");
      put("KeyGenerator." + ALGORITHM, keyGenerator);
      put(
          "KeyPairGenerator." + ALGORITHM,
          "CensusFlaky".equals(name) ? FlakyPairs.class.getName() : SteadyPairs.class.getName());
    }
  }

  /** Generates one key, then fails as a provider whose token went away would. */
  public static final class Flaky extends KeyGeneratorSpi {
    static int calls;

    @Override
    protected void engineInit(SecureRandom random) {}

    @Override
    protected void engineInit(AlgorithmParameterSpec params, SecureRandom random)
        throws InvalidAlgorithmParameterException {}

    @Override
    protected void engineInit(int keysize, SecureRandom random) {}

    @Override
    protected SecretKey engineGenerateKey() {
      if (calls++ > 0) {
        throw new ProviderException("token removed");
      }
      return new SecretKeySpec(new byte[16], "flaky");
    }
  }

  /** Always works. */
  public static final class Steady extends KeyGeneratorSpi {
    @Override
    protected void engineInit(SecureRandom random) {}

    @Override
    protected void engineInit(AlgorithmParameterSpec params, SecureRandom random)
        throws InvalidAlgorithmParameterException {}

    @Override
    protected void engineInit(int keysize, SecureRandom random) {}

    @Override
    protected SecretKey engineGenerateKey() {
      return new SecretKeySpec(new byte[16], "steady");
    }
  }

  /** Generates one key pair, then fails. */
  public static final class FlakyPairs extends KeyPairGeneratorSpi {
    static int calls;

    @Override
    public void initialize(int keysize, SecureRandom random) {}

    @Override
    public KeyPair generateKeyPair() {
      if (calls++ > 0) {
        throw new ProviderException("token removed");
      }
      return pair("flaky");
    }
  }

  /** Always works. */
  public static final class SteadyPairs extends KeyPairGeneratorSpi {
    @Override
    public void initialize(int keysize, SecureRandom random) {}

    @Override
    public KeyPair generateKeyPair() {
      return pair("steady");
    }
  }

  private static KeyPair pair(String algorithm) {
    return new KeyPair(new TestPublicKey(algorithm), new TestPrivateKey(algorithm));
  }

  private record TestPublicKey(String getAlgorithm) implements PublicKey {
    private static final long serialVersionUID = 1L;

    @Override
    public String getFormat() {
      return null;
    }

    @Override
    public byte[] getEncoded() {
      return BigInteger.ONE.toByteArray();
    }
  }

  private record TestPrivateKey(String getAlgorithm) implements PrivateKey {
    private static final long serialVersionUID = 1L;

    @Override
    public String getFormat() {
      return null;
    }

    @Override
    public byte[] getEncoded() {
      return BigInteger.ONE.toByteArray();
    }
  }
}
