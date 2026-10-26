package com.example.hybridrag.config;

import com.example.hybridrag.ingest.DocumentIngestService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动时自动加载样例语料，保证 /api/search 开箱即用。 */
@Component
public class SampleIngestRunner implements ApplicationRunner {

    private final DocumentIngestService ingest;

    public SampleIngestRunner(DocumentIngestService ingest) {
        this.ingest = ingest;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (ingest.chunkCount() == 0) {
            int docs = ingest.ingestSamples();
            System.out.println("[启动] 样例语料入库完成：" + docs + " 篇文档，" + ingest.chunkCount() + " 个片段");
        }
    }
}
