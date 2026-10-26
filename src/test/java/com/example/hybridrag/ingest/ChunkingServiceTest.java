package com.example.hybridrag.ingest;

import com.example.hybridrag.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkingServiceTest {

    private ChunkingService service(int maxChars, int overlap) {
        AppProperties props = new AppProperties(
                new AppProperties.Embedding(1024),
                new AppProperties.Chunk(maxChars, overlap),
                new AppProperties.Retrieval(60, 0.7, 0.3, 3, true, 300, 4));
        return new ChunkingService(props);
    }

    @Test
    void 按标题分段并保留章节上下文() {
        String body = "# 设备台账\n第一段内容。\n\n## 保养记录\n第二段内容。";
        List<ChunkingService.Chunk> chunks = service(1000, 50).chunk("doc", body);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().anyMatch(c -> c.sectionTitle().equals("设备台账")));
        assertTrue(chunks.stream().anyMatch(c -> c.sectionTitle().equals("保养记录")));
    }

    @Test
    void 窗口不超过最大字符数() {
        String body = "段落一内容。".repeat(300);
        List<ChunkingService.Chunk> chunks = service(200, 30).chunk("doc", body);
        assertTrue(chunks.size() > 1);
        chunks.forEach(c -> assertTrue(c.content().length() <= 200));
    }

    @Test
    void 空正文回退为单片段() {
        List<ChunkingService.Chunk> chunks = service(1000, 50).chunk("doc", "   \n");
        assertEquals(1, chunks.size());
        assertEquals("doc", chunks.get(0).sectionTitle());
    }
}
