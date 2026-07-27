package io.github.fatihcatalkaya.javatypst;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the properties that make this library usable from an Android app.
 *
 * <p>Android never ships {@code java.net.http} (JEP 321) at any API level, and loading a class
 * runs its static initializer, so anything referenced from {@code JavaTypst.<clinit>} must exist
 * on Android too.
 */
public class AndroidCompatibilityTest {

    @Test
    public void mainClassesDoNotReferenceJavaNetHttp() throws Exception {
        List<String> offenders = classesReferencing("java/net/http/");

        assertEquals(
                List.of(),
                offenders,
                "java.net.http does not exist on Android at any API level; use java.net.HttpURLConnection");
    }

    @Test
    public void loadingJavaTypstDoesNotCreateTheDefaultPackageResolver() throws Exception {
        assertStaticFieldIsNullAfterClassInitialization(
                "packageResolver",
                "JavaTypst.<clinit> must not construct HttpPackageResolver: on Android that crashes any"
                        + " static call on JavaTypst, including enableAot()");
    }

    @Test
    public void loadingJavaTypstDoesNotResolveTheDefaultPackageCacheDirectory() throws Exception {
        assertStaticFieldIsNullAfterClassInitialization(
                "packageCacheDir",
                "JavaTypst.<clinit> must not touch the filesystem layout; Android callers set their own"
                        + " cache directory via setPackageCacheDirectory");
    }

    private static void assertStaticFieldIsNullAfterClassInitialization(String fieldName, String message)
            throws Exception {
        // A fresh classloader gives us a JavaTypst class whose <clinit> has not run yet, so we can
        // observe exactly what class initialization alone does.
        try (URLClassLoader isolated = new URLClassLoader(classpath(), ClassLoader.getPlatformClassLoader())) {
            Class<?> javaTypst = Class.forName(JavaTypst.class.getName(), true, isolated);
            assertNotSame(JavaTypst.class, javaTypst, "expected an independently loaded class");

            Field field = javaTypst.getDeclaredField(fieldName);
            field.setAccessible(true);
            assertNull(field.get(null), message);
        }
    }

    private static List<String> classesReferencing(String symbol) throws Exception {
        Path classesDir = Paths.get(JavaTypst.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        byte[] needle = symbol.getBytes(US_ASCII);
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classesDir)) {
            for (Path classFile :
                    files.filter(p -> p.toString().endsWith(".class")).toList()) {
                if (contains(Files.readAllBytes(classFile), needle)) {
                    offenders.add(classesDir.relativize(classFile).toString());
                }
            }
        }
        return offenders;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static URL[] classpath() throws Exception {
        String[] entries = System.getProperty("java.class.path").split(File.pathSeparator);
        URL[] urls = new URL[entries.length];
        for (int i = 0; i < entries.length; i++) {
            urls[i] = Paths.get(entries[i]).toUri().toURL();
        }
        return urls;
    }
}
