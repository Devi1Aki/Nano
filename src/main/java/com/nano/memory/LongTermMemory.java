package com.nano.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 长期记忆 - 使用 SQLite 持久化跨会话的稳定事实。
 *
 * 内存 Map 用于当前进程快速读取，SQLite 是持久化事实来源。旧版本的
 * long_term_memory.json 会在首次启动时迁移一次，并通过 memory_meta 标记避免重复导入。
 */
public class LongTermMemory implements Memory {
    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);
    private static final String STORAGE_DIR_PROPERTY = "nano.memory.dir";
    private static final String STORAGE_DIR_ENV = "NANO_MEMORY_DIR";
    private static final String DATABASE_FILE = "long_term_memory.db";
    private static final String LEGACY_JSON_FILE = "long_term_memory.json";
    private static final String LEGACY_MIGRATION_KEY = "legacy_json_migrated";
    private static final TypeReference<List<Map<String, Object>>> LEGACY_LIST_TYPE = new TypeReference<>() {};

    private final Map<String, MemoryEntry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger tokenCounter = new AtomicInteger();
    private final ObjectMapper mapper = new ObjectMapper();
    private final File databaseFile;
    private final File legacyJsonFile;
    private final String jdbcUrl;

    public LongTermMemory() {
        this(resolveStorageDir());
    }

    public LongTermMemory(File storageDir) {
        File dir = storageDir;
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("无法创建长期记忆目录: {}", dir);
        }
        this.databaseFile = new File(dir, DATABASE_FILE);
        this.legacyJsonFile = new File(dir, LEGACY_JSON_FILE);
        this.jdbcUrl = "jdbc:sqlite:" + databaseFile.getAbsolutePath();

        initializeSchema();
        migrateLegacyJsonOnce();
        loadFromDatabase();
    }

    @Override
    public synchronized void store(MemoryEntry entry) {
        if (entry == null || entry.getId() == null || entry.getContent() == null) {
            return;
        }
        boolean duplicateContent = entries.values().stream()
                .anyMatch(existing -> !existing.getId().equals(entry.getId())
                        && existing.getContent().equals(entry.getContent()));
        if (duplicateContent) {
            return;
        }

        MemoryEntry previous = entries.get(entry.getId());
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO long_term_memory
                         (id, content, type, timestamp, metadata_json, token_count)
                     VALUES (?, ?, ?, ?, ?, ?)
                     ON CONFLICT(id) DO UPDATE SET
                         content = excluded.content,
                         type = excluded.type,
                         timestamp = excluded.timestamp,
                         metadata_json = excluded.metadata_json,
                         token_count = excluded.token_count
                     """)) {
            bindEntry(statement, entry);
            statement.executeUpdate();
            entries.put(entry.getId(), entry);
            if (previous != null) {
                tokenCounter.addAndGet(-previous.getTokenCount());
            }
            tokenCounter.addAndGet(entry.getTokenCount());
        } catch (SQLException | IOException e) {
            log.warn("长期记忆写入 SQLite 失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);
        return entries.values().stream()
                .filter(entry -> matches(entry, queryTokens))
                .sorted((left, right) -> right.getTimestamp().compareTo(left.getTimestamp()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryEntry> getAll() {
        return entries.values().stream()
                .sorted((left, right) -> left.getTimestamp().compareTo(right.getTimestamp()))
                .toList();
    }

    @Override
    public synchronized boolean delete(String id) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM long_term_memory WHERE id = ?")) {
            statement.setString(1, id);
            int affected = statement.executeUpdate();
            if (affected <= 0) {
                return false;
            }
            MemoryEntry removed = entries.remove(id);
            if (removed != null) {
                tokenCounter.addAndGet(-removed.getTokenCount());
            }
            return true;
        } catch (SQLException e) {
            log.warn("删除长期记忆失败: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public synchronized void clear() {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM long_term_memory");
            entries.clear();
            tokenCounter.set(0);
        } catch (SQLException e) {
            log.warn("清空长期记忆失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public int getTokenCount() {
        return tokenCounter.get();
    }

    @Override
    public int size() {
        return entries.size();
    }

    public List<MemoryEntry> getByType(MemoryEntry.MemoryType type) {
        return entries.values().stream()
                .filter(entry -> entry.getType() == type)
                .sorted((left, right) -> right.getTimestamp().compareTo(left.getTimestamp()))
                .toList();
    }

    public File getDatabaseFile() {
        return databaseFile;
    }

    private void initializeSchema() {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS long_term_memory (
                        id TEXT PRIMARY KEY,
                        content TEXT NOT NULL UNIQUE,
                        type TEXT NOT NULL,
                        timestamp TEXT NOT NULL,
                        metadata_json TEXT NOT NULL DEFAULT '{}',
                        token_count INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS memory_meta (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_long_term_memory_type
                    ON long_term_memory(type)
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_long_term_memory_timestamp
                    ON long_term_memory(timestamp DESC)
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("初始化长期记忆 SQLite 失败: " + databaseFile, e);
        }
    }

    private void loadFromDatabase() {
        entries.clear();
        tokenCounter.set(0);
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT id, content, type, timestamp, metadata_json, token_count
                     FROM long_term_memory
                     """)) {
            while (resultSet.next()) {
                MemoryEntry entry = readEntry(resultSet);
                entries.put(entry.getId(), entry);
                tokenCounter.addAndGet(entry.getTokenCount());
            }
            log.info("从 SQLite 加载了 {} 条长期记忆", entries.size());
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("加载长期记忆 SQLite 失败: " + databaseFile, e);
        }
    }

    private void migrateLegacyJsonOnce() {
        try (Connection connection = openConnection()) {
            if (metaExists(connection, LEGACY_MIGRATION_KEY)) {
                return;
            }
            connection.setAutoCommit(false);
            try {
                int migrated = migrateLegacyEntries(connection);
                writeMeta(connection, LEGACY_MIGRATION_KEY, Instant.now().toString());
                connection.commit();
                if (migrated > 0) {
                    log.info("已从 {} 迁移 {} 条长期记忆到 SQLite", legacyJsonFile, migrated);
                }
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception e) {
            log.warn("旧版长期记忆 JSON 迁移失败，将保留原文件: {}", e.getMessage(), e);
        }
    }

    private int migrateLegacyEntries(Connection connection) throws IOException, SQLException {
        if (!legacyJsonFile.exists()) {
            return 0;
        }
        List<Map<String, Object>> dataList = mapper.readValue(legacyJsonFile, LEGACY_LIST_TYPE);
        int migrated = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO long_term_memory
                    (id, content, type, timestamp, metadata_json, token_count)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            for (Map<String, Object> data : dataList) {
                MemoryEntry entry = mapToLegacyEntry(data);
                if (entry == null) {
                    continue;
                }
                bindEntry(statement, entry);
                migrated += statement.executeUpdate();
            }
        }
        return migrated;
    }

    private boolean metaExists(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM memory_meta WHERE key = ?")) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void writeMeta(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO memory_meta(key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    private void bindEntry(PreparedStatement statement, MemoryEntry entry) throws SQLException, IOException {
        statement.setString(1, entry.getId());
        statement.setString(2, entry.getContent());
        statement.setString(3, entry.getType().name());
        statement.setString(4, entry.getTimestamp().toString());
        statement.setString(5, mapper.writeValueAsString(entry.getMetadata()));
        statement.setInt(6, entry.getTokenCount());
    }

    private MemoryEntry readEntry(ResultSet resultSet) throws SQLException, IOException {
        String metadataJson = resultSet.getString("metadata_json");
        Map<String, String> metadata = metadataJson == null || metadataJson.isBlank()
                ? Map.of()
                : mapper.readValue(metadataJson, new TypeReference<>() {});
        return new MemoryEntry(
                resultSet.getString("id"),
                resultSet.getString("content"),
                MemoryEntry.MemoryType.valueOf(resultSet.getString("type")),
                Instant.parse(resultSet.getString("timestamp")),
                metadata,
                resultSet.getInt("token_count")
        );
    }

    @SuppressWarnings("unchecked")
    private MemoryEntry mapToLegacyEntry(Map<String, Object> map) {
        try {
            String id = (String) map.get("id");
            String content = (String) map.get("content");
            MemoryEntry.MemoryType type = MemoryEntry.MemoryType.valueOf((String) map.get("type"));
            Instant timestamp = map.get("timestamp") instanceof String value && !value.isBlank()
                    ? Instant.parse(value)
                    : Instant.now();
            Map<String, String> metadata = new HashMap<>();
            Object rawMetadata = map.get("metadata");
            if (rawMetadata instanceof Map<?, ?> metadataMap) {
                metadataMap.forEach((key, value) -> metadata.put(String.valueOf(key), String.valueOf(value)));
            }
            int tokenCount = map.get("tokenCount") instanceof Number number
                    ? number.intValue()
                    : MemoryEntry.estimateTokens(content);
            return new MemoryEntry(id, content, type, timestamp, metadata, tokenCount);
        } catch (Exception e) {
            log.warn("跳过无法迁移的长期记忆条目: {}", e.getMessage());
            return null;
        }
    }

    private boolean matches(MemoryEntry entry, Set<String> queryTokens) {
        if (MemoryQueryTokenizer.matches(entry.getContent(), queryTokens)) {
            return true;
        }
        return entry.getMetadata().values().stream()
                .anyMatch(value -> MemoryQueryTokenizer.matches(value, queryTokens));
    }

    private static File resolveStorageDir() {
        String configuredDir = System.getProperty(STORAGE_DIR_PROPERTY);
        if (configuredDir == null || configuredDir.isBlank()) {
            configuredDir = System.getenv(STORAGE_DIR_ENV);
        }
        if (configuredDir != null && !configuredDir.isBlank()) {
            return new File(configuredDir);
        }
        return new File(new File(System.getProperty("user.home"), ".nano"), "memory");
    }

    public String getStatusSummary() {
        Map<MemoryEntry.MemoryType, Long> typeCounts = entries.values().stream()
                .collect(Collectors.groupingBy(MemoryEntry::getType, Collectors.counting()));
        return String.format("长期记忆: %d条 / %d tokens (事实: %d, 摘要: %d, 工具结果: %d)",
                entries.size(), tokenCounter.get(),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.FACT, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.SUMMARY, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.TOOL_RESULT, 0L));
    }
}
