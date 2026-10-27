package it.context;
import org.junit.jupiter.api.Test;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
class ShadedIT {
    @Test void exercisesPhysicalShadedJar() throws Exception {
        URL jar = Path.of("target/shaded-1.jar").toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals("it.context.Archive")) return super.loadClass(name, resolve);
                Class<?> type = findLoadedClass(name);
                if (type == null) type = findClass(name);
                if (resolve) resolveClass(type);
                return type;
            }
        }) {
            loader.loadClass("it.context.Archive").getMethod("encrypt").invoke(null);
        }
    }
}
