package io.github.fatihcatalkaya.javatypst;

@FunctionalInterface
public interface TypstPackageResolver {
    /**
     * Returns raw .tar.gz bytes for the given Typst package.
     * Implementations must be thread-safe.
     */
    byte[] resolve(String namespace, String name, String version) throws TypstPackageNotFoundException;
}
