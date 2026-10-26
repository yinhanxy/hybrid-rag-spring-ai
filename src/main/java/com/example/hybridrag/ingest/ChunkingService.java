package com.example.hybridrag.ingest;

import com.example.hybridrag.config.AppProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构感知分块：先按标题 / 空行段落切段，再按字符窗口滑动切分并保留重叠。
 * 参数来自 application.yml，不照搬任何内部项目的固定阈值。
 */
@Service
public class ChunkingService {

    private final int maxChars;
    private final int overlapChars;

    public ChunkingService(AppProperties properties) {
        this.maxChars = properties.chunk().maxChars();
        this.overlapChars = Math.min(properties.chunk().overlapChars(), maxChars / 2);
    }

    public List<Chunk> chunk(String documentTitle, String body) {
        List<Section> sections = splitSections(body);
        List<Chunk> chunks = new ArrayList<>();
        int index = 0;
        for (Section section : sections) {
            String text = section.text().trim();
            if (text.isEmpty()) {
                continue;
            }
            List<String> windows = slideWindows(text);
            for (String window : windows) {
                chunks.add(new Chunk(window, index++, section.title()));
            }
        }
        if (chunks.isEmpty()) {
            chunks.add(new Chunk(documentTitle, 0, documentTitle));
        }
        return chunks;
    }

    /** 标题行（# / ##）与空行把正文切成段落，标题作为片段上下文。 */
    private List<Section> splitSections(String body) {
        List<Section> sections = new ArrayList<>();
        String currentTitle = "";
        StringBuilder paragraph = new StringBuilder();
        for (String rawLine : body.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.startsWith("#")) {
                flush(sections, currentTitle, paragraph);
                currentTitle = line.replaceAll("^#+\\s*", "").trim();
            } else if (line.isEmpty()) {
                flush(sections, currentTitle, paragraph);
            } else {
                paragraph.append(line).append("\n");
            }
        }
        flush(sections, currentTitle, paragraph);
        return sections;
    }

    private void flush(List<Section> sections, String title, StringBuilder paragraph) {
        if (paragraph.length() > 0) {
            sections.add(new Section(title, paragraph.toString()));
            paragraph.setLength(0);
        }
    }

    /** 按 maxChars 窗口滑动，相邻窗口保留 overlapChars 重叠。 */
    private List<String> slideWindows(String text) {
        List<String> windows = new ArrayList<>();
        int start = 0;
        int length = text.length();
        while (start < length) {
            int end = Math.min(start + maxChars, length);
            windows.add(text.substring(start, end).trim());
            if (end == length) {
                break;
            }
            start = Math.max(0, end - overlapChars);
        }
        return windows;
    }

    public record Chunk(String content, int index, String sectionTitle) {
    }

    private record Section(String title, String text) {
    }
}
