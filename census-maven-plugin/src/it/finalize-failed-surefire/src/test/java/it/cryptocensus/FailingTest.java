package it.cryptocensus;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.fail;
class FailingTest { @Test void leavesBrokenCryptoEvidenceBeforeFailing() throws Exception { MessageDigest.getInstance("MD5").digest(new byte[] {1}); fail("intentional producer failure"); } }
