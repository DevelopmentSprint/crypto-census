package com.developmentsprint.cryptocensus.it;

import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Concrete delegation, interface-only metadata and repeated real retransformation. */
public final class RetransformWorkload {
  private RetransformWorkload() {}

  public static void main(String[] args) throws Exception {
    ChildJvmCoverage.dumpAfterAgentShutdown();
    Service service = new Service();
    Work proxy =
        (Work)
            Proxy.newProxyInstance(
                Work.class.getClassLoader(),
                new Class<?>[] {Work.class},
                (ignored, method, arguments) -> {
                  try {
                    return method.invoke(service, arguments);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
    Service subclass = new SubclassProxy();
    int before = Boolean.parseBoolean(args[1]) ? bindings() : 0;
    for (int round = 0; round < 3; round++) {
      service.perform(false);
      proxy.perform(false);
      try {
        subclass.perform(true);
        throw new AssertionError("Application exception disappeared");
      } catch (ExpectedFailure expected) {
        raw(); // Caller is outside the failed annotated method; the parent must be restored.
      }
      proxy.interfaceOnly();
      Service.staticCall();
      RuntimeControlAgent.retransform(Service.class, SubclassProxy.class);
    }
    int after = Boolean.parseBoolean(args[1]) ? bindings() : 0;
    if (before != after || (Boolean.parseBoolean(args[1]) && before != 2)) {
      throw new AssertionError(
          "Retransformation grew the binding registry: " + before + "/" + after);
    }
    Files.writeString(Path.of(args[0]), "rounds=3;bindings=" + before + ";after=" + after);
  }

  private static int bindings() throws Exception {
    Class<?> facade =
        Class.forName("com.developmentsprint.cryptocensus.agent.AgentEnrichment", true, null);
    var current = facade.getDeclaredField("current");
    current.setAccessible(true);
    Object run = current.get(null);
    var catalog = run.getClass().getDeclaredField("catalog");
    catalog.setAccessible(true);
    Object value = catalog.get(run);
    var registry = value.getClass().getDeclaredField("bindings");
    registry.setAccessible(true);
    Object bindings = registry.get(value);
    var size = bindings.getClass().getDeclaredMethod("size");
    size.setAccessible(true);
    return (Integer) size.invoke(bindings);
  }

  static void raw() throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.ENCRYPT_MODE,
        new SecretKeySpec(new byte[16], "AES"),
        new GCMParameterSpec(128, new byte[12]));
    cipher.doFinal(new byte[16]);
  }

  public interface Work {
    void perform(boolean fail) throws Exception;

    @CryptoFlow("interface-only")
    void interfaceOnly() throws Exception;
  }

  public static class Service implements Work {
    @Override
    @CryptoFlow("instance")
    public void perform(boolean fail) throws Exception {
      raw();
      if (fail) {
        throw new ExpectedFailure();
      }
    }

    @Override
    public void interfaceOnly() throws Exception {
      raw();
    }

    @CryptoFlow("static")
    public static void staticCall() throws Exception {
      raw();
    }
  }

  public static final class SubclassProxy extends Service {
    @Override
    public void perform(boolean fail) throws Exception {
      super.perform(fail);
    }
  }

  private static final class ExpectedFailure extends Exception {
    private static final long serialVersionUID = 1L;
  }
}
