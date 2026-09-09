package com.nano.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

public final class EvalReportStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private final Path directory;

    public EvalReportStore(Path directory) {
        this.directory = directory;
    }

    public static EvalReportStore openDefault() {
        String configured = System.getProperty("nano.eval.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("NANO_EVAL_DIR");
        }
        Path path = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".nano", "eval")
                : Path.of(configured);
        return new EvalReportStore(path);
    }

    public Path write(EvalRunner.EvalReport report) throws IOException {
        Files.createDirectories(directory);
        Path output = directory.resolve("eval-" + FILE_TIME.format(Instant.now()) + ".json");
        EvalRunner.writeReport(report, output);
        return output;
    }

    public List<Path> reports() throws IOException {
        if (Files.notExists(directory)) {
            return List.of();
        }
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                    .toList();
        }
    }

    public EvalRunner.EvalReport read(String value) throws IOException {
        Path path = resolve(value);
        return MAPPER.readValue(path.toFile(), EvalRunner.EvalReport.class);
    }

    public Path resolve(String value) throws IOException {
        List<Path> reports = reports();
        if (value == null || value.isBlank() || "latest".equalsIgnoreCase(value)) {
            if (reports.isEmpty()) {
                throw new IOException("没有 Eval 报告");
            }
            return reports.get(0);
        }
        if ("previous".equalsIgnoreCase(value)) {
            if (reports.size() < 2) {
                throw new IOException("没有上一份 Eval 报告");
            }
            return reports.get(1);
        }
        Path direct = Path.of(value);
        if (Files.exists(direct)) {
            return direct;
        }
        Path inStore = directory.resolve(value);
        if (Files.exists(inStore)) {
            return inStore;
        }
        throw new IOException("找不到 Eval 报告: " + value);
    }
}
