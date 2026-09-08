package com.nano.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LongTermMemoryTest {
    @TempDir
    Path tempDir;

    private LongTermMemory memory;

    @BeforeEach
    void setUp() {
        memory = new LongTermMemory(tempDir.toFile());
    }

    @Test
    void shouldStoreAndRetrieve() {
        MemoryEntry entry = new MemoryEntry("fact-1", "项目使用Java 17", MemoryEntry.MemoryType.FACT, null, 10);
        memory.store(entry);

        assertTrue(memory.retrieve("fact-1").isPresent());
        assertEquals("项目使用Java 17", memory.retrieve("fact-1").get().getContent());
    }

    @Test
    void shouldDeduplicateSameContent() {
        MemoryEntry entry1 = new MemoryEntry("fact-1", "相同内容", MemoryEntry.MemoryType.FACT, null, 5);
        MemoryEntry entry2 = new MemoryEntry("fact-2", "相同内容", MemoryEntry.MemoryType.FACT, null, 5);

        memory.store(entry1);
        memory.store(entry2);

        assertEquals(1, memory.size());
    }

    @Test
    void shouldSearchByKeywords() {
        memory.store(new MemoryEntry("f1", "用户偏好使用IntelliJ IDEA", MemoryEntry.MemoryType.FACT, null, 10));
        memory.store(new MemoryEntry("f2", "项目路径: /home/user/project", MemoryEntry.MemoryType.FACT, null, 10));

        var results = memory.search("IntelliJ", 5);
        assertEquals(1, results.size());
    }

    @Test
    void shouldSearchByMultipleKeywords() {
        memory.store(new MemoryEntry("f1", "用户偏好使用Java开发", MemoryEntry.MemoryType.FACT, null, 10));

        var results = memory.search("Java 偏好", 5);
        assertFalse(results.isEmpty());
    }

    @Test
    void shouldSearchChineseWithoutRelyingOnSpaces() {
        memory.store(new MemoryEntry("f1", "用户偏好使用Java开发", MemoryEntry.MemoryType.FACT, null, 10));

        var results = memory.search("偏好设置", 5);
        assertFalse(results.isEmpty());
    }

    @Test
    void shouldDeleteEntry() {
        memory.store(new MemoryEntry("f1", "测试内容", MemoryEntry.MemoryType.FACT, null, 5));
        assertTrue(memory.delete("f1"));
        assertEquals(0, memory.size());
    }

    @Test
    void shouldFilterByType() {
        memory.store(new MemoryEntry("f1", "事实1", MemoryEntry.MemoryType.FACT, null, 5));
        memory.store(new MemoryEntry("s1", "摘要1", MemoryEntry.MemoryType.SUMMARY, null, 5));

        var facts = memory.getByType(MemoryEntry.MemoryType.FACT);
        assertEquals(1, facts.size());
    }

    @Test
    void shouldPersistAndReload() {
        memory.store(new MemoryEntry("f1", "持久化测试内容", MemoryEntry.MemoryType.FACT, null, 10));
        memory.store(new MemoryEntry("s1", "摘要测试", MemoryEntry.MemoryType.SUMMARY, null, 8));

        assertTrue(Files.exists(tempDir.resolve("long_term_memory.db")));

        LongTermMemory reloaded = new LongTermMemory(tempDir.toFile());
        assertEquals(2, reloaded.size());
        assertTrue(reloaded.retrieve("f1").isPresent());
    }

    @Test
    void shouldPreserveTimestampAfterReload() {
        Instant timestamp = Instant.parse("2026-04-20T12:34:56Z");
        memory.store(new MemoryEntry("f1", "带时间戳的事实", MemoryEntry.MemoryType.FACT, timestamp, null, 10));

        LongTermMemory reloaded = new LongTermMemory(tempDir.toFile());
        assertEquals(timestamp, reloaded.retrieve("f1").orElseThrow().getTimestamp());
    }

    @Test
    void shouldMigrateLegacyJsonOnlyOnce() throws Exception {
        Path migrationDir = tempDir.resolve("legacy-migration");
        Files.createDirectories(migrationDir);
        Path legacyFile = migrationDir.resolve("long_term_memory.json");
        new ObjectMapper().writeValue(legacyFile.toFile(), List.of(Map.of(
                "id", "legacy-1",
                "content", "旧版长期记忆",
                "type", "FACT",
                "timestamp", "2026-04-20T12:34:56Z",
                "metadata", Map.of("source", "legacy"),
                "tokenCount", 12
        )));

        LongTermMemory migrated = new LongTermMemory(migrationDir.toFile());
        assertEquals(1, migrated.size());
        assertEquals("legacy", migrated.retrieve("legacy-1").orElseThrow()
                .getMetadata().get("source"));

        migrated.clear();
        LongTermMemory reloaded = new LongTermMemory(migrationDir.toFile());
        assertEquals(0, reloaded.size(), "迁移标记应避免清空后再次导入旧 JSON");
    }
}
