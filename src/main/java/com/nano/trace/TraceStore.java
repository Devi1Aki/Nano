package com.nano.trace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TraceStore implements AutoCloseable {
    private final Connection connection;

    public TraceStore(Path dbPath) throws SQLException {
        try {
            Path parent = dbPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new SQLException("无法创建 Trace 数据库目录: " + e.getMessage(), e);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        initSchema();
    }

    public static TraceStore openDefault() throws SQLException {
        return new TraceStore(defaultDbPath());
    }

    public static Path defaultDbPath() {
        String configured = System.getProperty("nano.trace.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("NANO_TRACE_DIR");
        }
        Path directory = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".nano", "traces")
                : Path.of(configured);
        return directory.resolve("traces.db");
    }

    public synchronized String startTrace(String mode, String promptPreview, String provider, String model) {
        String id = "trace_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO traces (id, mode, prompt_preview, provider, model, status, started_at)
                VALUES (?, ?, ?, ?, ?, 'running', ?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, valueOrUnknown(mode));
            statement.setString(3, TraceSanitizer.sanitizeAndTruncate(promptPreview, 300));
            statement.setString(4, valueOrUnknown(provider));
            statement.setString(5, valueOrUnknown(model));
            statement.setString(6, Instant.now().toString());
            statement.executeUpdate();
            return id;
        } catch (SQLException e) {
            throw new IllegalStateException("创建 Trace 失败: " + e.getMessage(), e);
        }
    }

    public synchronized void appendEvent(String traceId, String type, String name, String status,
                                         long durationMs, String data) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO trace_events (trace_id, type, name, status, duration_ms, data, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, traceId);
            statement.setString(2, valueOrUnknown(type));
            statement.setString(3, name);
            statement.setString(4, valueOrUnknown(status));
            statement.setLong(5, Math.max(0L, durationMs));
            statement.setString(6, TraceSanitizer.sanitizeAndTruncate(data, 4000));
            statement.setString(7, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("写入 Trace 事件失败: " + e.getMessage(), e);
        }
    }

    public synchronized void finishTrace(String traceId, String status, long durationMs,
                                         long inputTokens, long outputTokens, long cachedInputTokens,
                                         int llmCalls, int toolCalls, String error) {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE traces
                SET status = ?, finished_at = ?, duration_ms = ?, input_tokens = ?, output_tokens = ?,
                    cached_input_tokens = ?, llm_calls = ?, tool_calls = ?, error = ?
                WHERE id = ?
                """)) {
            statement.setString(1, valueOrUnknown(status));
            statement.setString(2, Instant.now().toString());
            statement.setLong(3, Math.max(0L, durationMs));
            statement.setLong(4, Math.max(0L, inputTokens));
            statement.setLong(5, Math.max(0L, outputTokens));
            statement.setLong(6, Math.max(0L, cachedInputTokens));
            statement.setInt(7, Math.max(0, llmCalls));
            statement.setInt(8, Math.max(0, toolCalls));
            statement.setString(9, TraceSanitizer.sanitizeAndTruncate(error, 1000));
            statement.setString(10, traceId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("完成 Trace 失败: " + e.getMessage(), e);
        }
    }

    public synchronized List<TraceSummary> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<TraceSummary> traces = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, mode, prompt_preview, provider, model, status, started_at, finished_at,
                       duration_ms, input_tokens, output_tokens, cached_input_tokens, llm_calls, tool_calls, error
                FROM traces ORDER BY started_at DESC LIMIT ?
                """)) {
            statement.setInt(1, safeLimit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    traces.add(readSummary(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 Trace 列表失败: " + e.getMessage(), e);
        }
        return traces;
    }

    public synchronized TraceSummary find(String traceId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, mode, prompt_preview, provider, model, status, started_at, finished_at,
                       duration_ms, input_tokens, output_tokens, cached_input_tokens, llm_calls, tool_calls, error
                FROM traces WHERE id = ?
                """)) {
            statement.setString(1, traceId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? readSummary(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 Trace 失败: " + e.getMessage(), e);
        }
    }

    public synchronized List<TraceEvent> events(String traceId) {
        List<TraceEvent> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, trace_id, type, name, status, duration_ms, data, created_at
                FROM trace_events WHERE trace_id = ? ORDER BY id ASC
                """)) {
            statement.setString(1, traceId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    events.add(new TraceEvent(
                            rs.getLong("id"),
                            rs.getString("trace_id"),
                            rs.getString("type"),
                            rs.getString("name"),
                            rs.getString("status"),
                            rs.getLong("duration_ms"),
                            rs.getString("data"),
                            Instant.parse(rs.getString("created_at"))
                    ));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 Trace 事件失败: " + e.getMessage(), e);
        }
        return events;
    }

    private void initSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS traces (
                        id TEXT PRIMARY KEY,
                        mode TEXT NOT NULL,
                        prompt_preview TEXT,
                        provider TEXT NOT NULL,
                        model TEXT NOT NULL,
                        status TEXT NOT NULL,
                        started_at TEXT NOT NULL,
                        finished_at TEXT,
                        duration_ms INTEGER NOT NULL DEFAULT 0,
                        input_tokens INTEGER NOT NULL DEFAULT 0,
                        output_tokens INTEGER NOT NULL DEFAULT 0,
                        cached_input_tokens INTEGER NOT NULL DEFAULT 0,
                        llm_calls INTEGER NOT NULL DEFAULT 0,
                        tool_calls INTEGER NOT NULL DEFAULT 0,
                        error TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS trace_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        trace_id TEXT NOT NULL,
                        type TEXT NOT NULL,
                        name TEXT,
                        status TEXT NOT NULL,
                        duration_ms INTEGER NOT NULL DEFAULT 0,
                        data TEXT,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY(trace_id) REFERENCES traces(id)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_traces_started ON traces(started_at DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_trace_events_trace ON trace_events(trace_id, id)");
        }
    }

    private static TraceSummary readSummary(ResultSet rs) throws SQLException {
        String finished = rs.getString("finished_at");
        return new TraceSummary(
                rs.getString("id"), rs.getString("mode"), rs.getString("prompt_preview"),
                rs.getString("provider"), rs.getString("model"), rs.getString("status"),
                Instant.parse(rs.getString("started_at")),
                finished == null ? null : Instant.parse(finished),
                rs.getLong("duration_ms"), rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                rs.getLong("cached_input_tokens"), rs.getInt("llm_calls"), rs.getInt("tool_calls"),
                rs.getString("error")
        );
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    public record TraceSummary(
            String id, String mode, String promptPreview, String provider, String model, String status,
            Instant startedAt, Instant finishedAt, long durationMs, long inputTokens, long outputTokens,
            long cachedInputTokens, int llmCalls, int toolCalls, String error
    ) {
    }

    public record TraceEvent(
            long id, String traceId, String type, String name, String status,
            long durationMs, String data, Instant createdAt
    ) {
    }
}
