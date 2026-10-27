package it.context;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
class ArchiveIT {
    @Test void usesPackagedLibrary() throws Exception {
        assertTrue(Archive.class.getProtectionDomain().getCodeSource().getLocation().toString().endsWith("library-1.jar"));
        Archive.encrypt();
    }
}
