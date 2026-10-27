package com.developmentsprint.cryptocensus.appservers;

import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import com.developmentsprint.cryptocensus.annotations.CryptoPurpose;
import com.developmentsprint.cryptocensus.annotations.DataProtection;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Same annotated binary deployed independently in two real application-server WARs. */
@WebServlet("/crypto")
public final class AnnotatedCryptoServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      byte[] digest = archive(request.getRequestURI());
      // This entry point follows both annotated exits and must have no active business scope.
      MessageDigest.getInstance("SHA-256").digest(digest);
      response.setHeader(
          "X-Census-Code-Source",
          AnnotatedCryptoServlet.class
              .getProtectionDomain()
              .getCodeSource()
              .getLocation()
              .toExternalForm());
      response.setHeader(
          "X-Census-Loader-Name",
          String.valueOf(AnnotatedCryptoServlet.class.getClassLoader().getName()));
      response.setContentType("text/plain");
      response.getWriter().write("ok " + HexFormat.of().formatHex(digest));
    } catch (GeneralSecurityException failure) {
      throw new IOException(failure);
    }
  }

  @CryptoFlow(value = "archive", purpose = CryptoPurpose.STORAGE)
  @DataProtection(classification = "confidential", confidentialityYears = 20)
  private static byte[] archive(String input) throws GeneralSecurityException {
    byte[] digest =
        MessageDigest.getInstance("SHA3-256").digest(input.getBytes(StandardCharsets.UTF_8));
    transport(digest);
    return digest;
  }

  @CryptoFlow(value = "transport", purpose = CryptoPurpose.TRANSPORT)
  @DataProtection(confidentialityYears = 5)
  private static void transport(byte[] payload) throws GeneralSecurityException {
    KeyGenerator generator = KeyGenerator.getInstance("AES");
    generator.init(256);
    SecretKey key = generator.generateKey();
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, new byte[12]));
    cipher.doFinal(payload);
  }
}
