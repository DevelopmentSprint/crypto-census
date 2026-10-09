package com.developmentsprint.cryptocensus.appservers;

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

/**
 * Deployed to the application servers in a WAR built by {@link AppServerIT}: application code doing
 * cryptography inside a request, the call site the CBOM must attribute.
 */
@WebServlet("/crypto")
public final class CryptoServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    byte[] digest;
    try {
      digest =
          MessageDigest.getInstance("SHA3-256")
              .digest(request.getRequestURI().getBytes(StandardCharsets.UTF_8));
      KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      SecretKey key = generator.generateKey();
      Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
      gcm.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, new byte[12]));
      gcm.doFinal(digest);
    } catch (GeneralSecurityException e) {
      throw new IOException(e);
    }
    response.setContentType("text/plain");
    response.getWriter().write("ok " + HexFormat.of().formatHex(digest));
  }
}
