package com.nano.eval;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class EvalWorkspace implements AutoCloseable {
    private static final Set<String> EXCLUDED_ROOT_NAMES =
            Set.of(".git", ".idea", ".nano", ".env", "target");
    private final Path path;
    private final Path cleanupRoot;
    private final boolean isolated;
    private final Map<String, FileStamp> before;

    private EvalWorkspace(Path path, Path cleanupRoot, boolean isolated) throws IOException {
        this.path = path.toAbsolutePath().normalize();
        this.cleanupRoot = cleanupRoot.toAbsolutePath().normalize();
        this.isolated = isolated;
        this.before = isolated ? snapshot(this.path) : Map.of();
    }

    public static EvalWorkspace prepare(BenchmarkCase benchmarkCase, Path projectRoot) throws IOException {
        Path source = projectRoot.toAbsolutePath().normalize();
        if ("mcp".equals(benchmarkCase.category())) {
            Path container = Files.createTempDirectory("nano-eval-" + safeName(benchmarkCase.id()) + "-");
            return new EvalWorkspace(source, container, false);
        }
        Path container = Files.createTempDirectory("nano-eval-" + safeName(benchmarkCase.id()) + "-");
        Path workspace = Files.createDirectories(container.resolve("workspace"));
        Path fixture = source.resolve("benchmarks/fixtures/java-project");
        if ("editing".equals(benchmarkCase.category()) || "safety".equals(benchmarkCase.category())) {
            copyTree(fixture, workspace, false);
        } else {
            copyTree(source, workspace, true);
        }
        return new EvalWorkspace(workspace, container, true);
    }

    public Path path() {
        return path;
    }

    public boolean isolated() {
        return isolated;
    }

    public Path statePath() throws IOException {
        return Files.createDirectories(cleanupRoot.resolve("state"));
    }

    public String diffSummary() throws IOException {
        if (!isolated) {
            return "workspace=shared (MCP case)";
        }
        Map<String, FileStamp> after = snapshot(path);
        List<String> changes = new ArrayList<>();
        for (String file : before.keySet()) {
            if (!after.containsKey(file)) {
                changes.add("deleted " + file);
            } else if (!before.get(file).equals(after.get(file))) {
                changes.add("modified " + file);
            }
        }
        for (String file : after.keySet()) {
            if (!before.containsKey(file)) {
                changes.add("created " + file);
            }
        }
        changes.sort(String::compareTo);
        return changes.isEmpty() ? "workspace_diff: clean" : "workspace_diff:\n" + String.join("\n", changes);
    }

    @Override
    public void close() throws IOException {
        if (Files.notExists(cleanupRoot)) {
            return;
        }
        try (var paths = Files.walk(cleanupRoot)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    private static void copyTree(Path source, Path target, boolean applyRootExclusions) throws IOException {
        if (!Files.isDirectory(source)) {
            throw new IOException("Eval fixture 不存在: " + source);
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                if (applyRootExclusions && relative.getNameCount() == 1
                        && EXCLUDED_ROOT_NAMES.contains(relative.toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(relative.toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                if (!Files.isSymbolicLink(file)
                        && !(applyRootExclusions && relative.getNameCount() == 1
                        && EXCLUDED_ROOT_NAMES.contains(relative.toString()))) {
                    Files.copy(file, target.resolve(relative.toString()));
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Map<String, FileStamp> snapshot(Path root) throws IOException {
        Map<String, FileStamp> values = new HashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(candidate -> !isGeneratedPath(root.relativize(candidate)))
                    .toList()) {
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                values.put(root.relativize(file).toString(), new FileStamp(attrs.size(), sha256(file)));
            }
        }
        return values;
    }

    private static boolean isGeneratedPath(Path relative) {
        return relative.getNameCount() > 0
                && Set.of(".git", ".nano", "target").contains(relative.getName(0).toString());
    }

    private static String safeName(String value) {
        return value == null ? "case" : value.replaceAll("[^A-Za-z0-9_-]", "-");
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private record FileStamp(long size, String sha256) {
    }
}
