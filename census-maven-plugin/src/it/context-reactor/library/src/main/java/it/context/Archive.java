package it.context;
import com.developmentsprint.cryptocensus.annotations.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.GCMParameterSpec;
public final class Archive {
    @CryptoFlow(value="archive", purpose=CryptoPurpose.STORAGE)
    @DataProtection(confidentialityYears=20)
    public static void encrypt() throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(new byte[16],"AES"),new GCMParameterSpec(128,new byte[12]));
        cipher.doFinal(new byte[16]);
    }
}
