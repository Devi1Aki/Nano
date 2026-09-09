package com.nano.fixture;

import java.nio.file.Path;

public final class PathNormalizer {
    public Path sourcePath(String root, String child) {
        return Path.of(root).resolve(child).toAbsolutePath().normalize();
    }

    public Path outputPath(String root, String child) {
        return Path.of(root).resolve(child).toAbsolutePath().normalize();
    }
}
