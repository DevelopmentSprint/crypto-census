package com.developmentsprint.cryptocensus.agent;

import com.developmentsprint.cryptocensus.core.CryptoAsset;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Occurrence;
import com.developmentsprint.cryptocensus.core.Operation;
import com.developmentsprint.cryptocensus.core.Primitive;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.Signature;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.net.ssl.SSLSession;

/**
 * Receives callbacks from the inlined advice and records assets into the inventory.
 *
 * <p>Public because it is called from code inlined into {@code java.base}. Not an API: code in the
 * observed application can call it too, which is one reason a CBOM is self-reported evidence.
 */
public final class Recorder {

  private static volatile Inventory inventory = Inventory.bounded(500, 50);
  private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

  /**
   * Last SSLSessionImpl recorded on this thread; constructor chaining runs exit advice once per
   * constructor.
   */
  private static final ThreadLocal<WeakReference<Object>> LAST_CONSTRUCTED = new ThreadLocal<>();

  private static final LongAdder FAILURES = new LongAdder();
  private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
  private static volatile boolean enabled;

  private Recorder() {}

  static void configure(int maxAssets, int maxSitesPerAsset) {
    inventory = Inventory.bounded(maxAssets, maxSitesPerAsset);
  }

  static void enable() {
    enabled = true;
  }

  static void disable() {
    enabled = false;
  }

  static Inventory inventory() {
    return inventory;
  }

  /**
   * Observations that threw inside the recorder (and were swallowed). Non-zero means incomplete
   * data.
   */
  static long failures() {
    return FAILURES.sum();
  }

  /** Recording calls currently executing; shutdown waits briefly for these to drain. */
  static int inFlight() {
    return IN_FLIGHT.get();
  }

  /**
   * Called on entry to every instrumented method. Returns the nesting depth, or 0 when disabled.
   * Only the outermost call (depth 1) is recorded: {@code Cipher.init(int, Key)} delegating to
   * another {@code init} overload, or a signature computing a digest internally, is one use.
   *
   * <p>An outermost call counts as in flight from admission until its exit advice finishes, so the
   * shutdown drain sees operations that are still inside the provider.
   */
  public static int enter() {
    int[] depth = DEPTH.get();
    if (depth[0] == 0) {
      // Register first, then check: shutdown disables and then waits for IN_FLIGHT == 0. With both
      // being sequentially consistent, either shutdown sees this registration or we see the
      // disable and back out; checking first would leave a window where neither happens.
      IN_FLIGHT.incrementAndGet();
      Runnable hook = afterAdmissionHook;
      if (hook != null) hook.run();
      if (!enabled) {
        IN_FLIGHT.decrementAndGet();
        return 0;
      }
    } else if (!enabled) {
      return 0;
    }
    return ++depth[0];
  }

  /** Test seam: runs between in-flight registration and the enabled check. Null in production. */
  static volatile Runnable afterAdmissionHook;

  /**
   * @param detail {@code Integer} cipher opmode, or {@code String} method name (initSign /
   *     initVerify)
   */
  public static void exit(int depth, Object self, Object arg, Object detail, Throwable thrown) {
    if (depth <= 0) return;
    try {
      if (depth == 1 && thrown == null && self != null) {
        record(self, arg, detail);
      }
    } catch (Throwable t) {
      FAILURES.increment();
    } finally {
      DEPTH.get()[0]--;
      if (depth == 1) IN_FLIGHT.decrementAndGet();
    }
  }

  /** Records an object right after construction, unless we are inside another instrumented call. */
  public static void constructed(Object self) {
    if (!enabled) return;
    int[] depth = DEPTH.get();
    if (depth[0] != 0) return;
    WeakReference<Object> last = LAST_CONSTRUCTED.get();
    if (last != null && last.get() == self) return; // chained constructor of the same object
    IN_FLIGHT.incrementAndGet(); // register before re-checking, as in enter()
    if (!enabled) {
      IN_FLIGHT.decrementAndGet();
      return;
    }
    LAST_CONSTRUCTED.set(new WeakReference<>(self));
    depth[0]++;
    try {
      record(self, null, null);
    } catch (Throwable t) {
      FAILURES.increment();
    } finally {
      depth[0]--;
      IN_FLIGHT.decrementAndGet();
    }
  }

  // ------------------------------------------------------------------------------------------

  private static void record(Object self, Object arg, Object detail) {
    CryptoAsset asset = toAsset(self, arg, detail);
    if (asset != null) {
      Occurrence occurrence = Callsites.current();
      if (asset.primitive() == Primitive.TLS && occurrence.via() == null) {
        occurrence =
            new Occurrence(occurrence.location(), occurrence.symbol(), occurrence.line(), "jsse");
      }
      inventory.record(asset, occurrence);
    }
  }

  /**
   * The provider's own canonical name for the algorithm that served this call. Any alias or OID the
   * provider accepts ({@code DSS}, {@code 1.3.14.3.2.29}, ...) resolves to its standard name, so a
   * spelling the policy's static table does not know cannot become "unknown".
   */
  static String resolve(Provider provider, String type, String algorithm) {
    if (provider == null || algorithm == null) return algorithm;
    try {
      Provider.Service service = provider.getService(type, algorithm.trim());
      return service != null ? service.getAlgorithm() : algorithm;
    } catch (RuntimeException e) {
      return algorithm;
    }
  }

  /**
   * Resolves a cipher transformation the way JCA looks it up: the full {@code alg/mode/padding}
   * first, then {@code alg/mode}, {@code alg//padding} and finally {@code alg}. The result is
   * rebuilt from the <em>canonical service's</em> own shape: whatever mode or padding the canonical
   * name carries wins, the rest comes from the request. So a provider aliasing {@code
   * VendorAES/CBC/PKCS5Padding} to {@code AES/CBC/PKCS5Padding}, or {@code VendorAES/CBC} to plain
   * {@code AES}, resolves to {@code AES/CBC/PKCS5Padding} either way.
   */
  static String resolveTransformation(Provider provider, String transformation) {
    if (transformation == null || provider == null) return transformation;
    String normalized = transformation.replaceAll("\\s+", "");
    String[] req = normalized.split("/", 3); // padding may itself contain '/'
    String reqAlg = req[0];
    String reqMode = req.length > 1 ? req[1] : null;
    String reqPad = req.length > 2 ? req[2] : null;

    String canonical = null;
    if (reqMode != null && reqPad != null) canonical = service(provider, normalized);
    if (canonical == null && reqMode != null) canonical = service(provider, reqAlg + "/" + reqMode);
    if (canonical == null && reqPad != null) canonical = service(provider, reqAlg + "//" + reqPad);
    if (canonical == null) canonical = service(provider, reqAlg);
    if (canonical == null) return normalized;

    String[] can = canonical.split("/", 3);
    String alg = can[0];
    String mode = can.length > 1 && !can[1].isEmpty() ? can[1] : reqMode;
    String pad = can.length > 2 && !can[2].isEmpty() ? can[2] : reqPad;
    if (mode == null && pad == null) return alg;
    return alg + "/" + (mode == null ? "" : mode) + (pad == null ? "" : "/" + pad);
  }

  private static String service(Provider provider, String name) {
    try {
      Provider.Service s = provider.getService("Cipher", name);
      return s == null ? null : s.getAlgorithm();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static CryptoAsset toAsset(Object self, Object arg, Object detail) {
    if (self instanceof Cipher) {
      Cipher c = (Cipher) self;
      Operation op = detail instanceof Integer ? Operation.ofCipherMode((Integer) detail) : null;
      Provider p = c.getProvider();
      return CryptoAsset.cipher(
          resolveTransformation(p, c.getAlgorithm()), KeyInfo.size(arg), name(p), op);
    }
    if (self instanceof Signature) {
      Signature s = (Signature) self;
      Operation op =
          "initSign".equals(detail)
              ? Operation.SIGN
              : "initVerify".equals(detail) ? Operation.VERIFY : null;
      Provider p = s.getProvider();
      return CryptoAsset.of(
          Primitive.SIGNATURE,
          KeyInfo.refine(resolve(p, "Signature", s.getAlgorithm()), arg),
          KeyInfo.size(arg),
          name(p),
          op);
    }
    if (self instanceof Mac) {
      Mac m = (Mac) self;
      return CryptoAsset.of(
          Primitive.MAC,
          resolve(m.getProvider(), "Mac", m.getAlgorithm()),
          KeyInfo.size(arg),
          name(m.getProvider()));
    }
    if (self instanceof KeyAgreement) {
      KeyAgreement k = (KeyAgreement) self;
      return CryptoAsset.of(
          Primitive.KEY_AGREEMENT,
          KeyInfo.refine(resolve(k.getProvider(), "KeyAgreement", k.getAlgorithm()), arg),
          KeyInfo.size(arg),
          name(k.getProvider()));
    }
    if (self instanceof KeyGenerator) {
      KeyGenerator g = (KeyGenerator) self;
      return CryptoAsset.of(
          Primitive.KEY_GENERATION,
          resolve(g.getProvider(), "KeyGenerator", g.getAlgorithm()),
          KeyInfo.size(arg),
          name(g.getProvider()));
    }
    if (self instanceof KeyPairGenerator) {
      KeyPairGenerator g = (KeyPairGenerator) self;
      Object publicKey = arg instanceof KeyPair ? ((KeyPair) arg).getPublic() : null;
      return CryptoAsset.of(
          Primitive.KEY_PAIR_GENERATION,
          KeyInfo.refine(resolve(g.getProvider(), "KeyPairGenerator", g.getAlgorithm()), publicKey),
          KeyInfo.size(publicKey),
          name(g.getProvider()));
    }
    if (self instanceof MessageDigest) {
      MessageDigest d = (MessageDigest) self;
      return CryptoAsset.of(
          Primitive.MESSAGE_DIGEST,
          resolve(d.getProvider(), "MessageDigest", d.getAlgorithm()),
          0,
          name(d.getProvider()));
    }
    if (self instanceof SSLSession) {
      SSLSession session = (SSLSession) self;
      String protocol = session.getProtocol();
      String suite = session.getCipherSuite();
      // JSSE's placeholder session before negotiation
      if (protocol == null
          || suite == null
          || "NONE".equals(protocol)
          || suite.contains("_NULL_WITH_NULL_")) {
        return null;
      }
      return CryptoAsset.tls(protocol, suite);
    }
    if (self.getClass().getName().equals("javax.crypto.KEM")) {
      // javax.crypto.KEM is JDK 21+; this agent is compiled for 17
      return CryptoAsset.of(
          Primitive.KEM, KeyInfo.refine(kemAlgorithm(self), arg), KeyInfo.size(arg), null);
    }
    return null;
  }

  private static volatile Method kemGetAlgorithm;

  private static String kemAlgorithm(Object kem) {
    try {
      Method m = kemGetAlgorithm;
      if (m == null) {
        m = kem.getClass().getMethod("getAlgorithm");
        kemGetAlgorithm = m;
      }
      return (String) m.invoke(kem);
    } catch (ReflectiveOperationException e) {
      return "KEM";
    }
  }

  private static String name(Provider provider) {
    return provider == null ? null : provider.getName();
  }
}
