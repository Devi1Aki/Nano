package com.nano.rag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeIndexTest {

    private static EmbeddingClient offlineEmbeddingClient() {
        return new EmbeddingClient("mock", "mock", "http://localhost", "") {
            @Override
            public float[] embed(String text) {
                // Tests should not depend on Ollama / network.
                return new float[] {0.0f, 1.0f, 0.0f};
            }
        };
    }

    private static CodeIndex createOfflineIndexer() {
        return new CodeIndex(offlineEmbeddingClient());
    }

    @Test
    void testIndexNonExistentPath() {
        CodeIndex indexer = new CodeIndex();
        CodeIndex.IndexResult result = indexer.index("/non/existent/path");
        assertEquals(0, result.chunkCount());
        assertTrue(result.message().contains("路径不存在"));
    }

    @Test
    void testIndexCurrentProject() {
        System.setProperty("nano.rag.dir", "/tmp/nano-test-rag-index");
        CodeIndex indexer = createOfflineIndexer();
        // 索引测试资源目录
        CodeIndex.IndexResult result = indexer.index("src/test/resources/rag");
        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(result.message().contains("索引完成"));
    }

    @Test
    void reportsProgressThroughListener() {
        List<String> messages = new ArrayList<>();
        CodeIndex indexer = new CodeIndex(offlineEmbeddingClient(), messages::add);

        CodeIndex.IndexResult result = indexer.index("src/test/resources/rag");

        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("🔍 开始索引")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("📁 发现")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("✅ 索引完成")));
    }
}
