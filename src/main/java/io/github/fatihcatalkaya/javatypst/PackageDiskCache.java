package io.github.fatihcatalkaya.javatypst;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

final class PackageDiskCache {

    private final Path cacheDir;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    PackageDiskCache(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    byte[] get(String namespace, String name, String version, TypstPackageResolver resolver)
            throws TypstPackageNotFoundException {
        Path archivePath = cacheDir.resolve(namespace).resolve(name).resolve(version + ".tar.gz");
        try {
            if (Files.exists(archivePath)) {
                return Files.readAllBytes(archivePath);
            }
            Object lock = locks.computeIfAbsent(namespace + "/" + name + "/" + version, k -> new Object());
            synchronized (lock) {
                if (Files.exists(archivePath)) {
                    return Files.readAllBytes(archivePath);
                }
                byte[] bytes = resolver.resolve(namespace, name, version);
                Files.createDirectories(archivePath.getParent());
                Files.write(archivePath, bytes);
                return bytes;
            }
        } catch (IOException e) {
            throw new RuntimeException("Disk cache read/write failed", e);
        }
    }
}
