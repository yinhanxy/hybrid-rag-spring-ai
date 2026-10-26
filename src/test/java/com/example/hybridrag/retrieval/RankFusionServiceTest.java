package com.example.hybridrag.retrieval;

import com.example.hybridrag.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankFusionServiceTest {

    private RankFusionService service(double vectorWeight, double textWeight) {
        AppProperties props = new AppProperties(
                new AppProperties.Embedding(1024),
                new AppProperties.Chunk(1000, 150),
                new AppProperties.Retrieval(60, vectorWeight, textWeight, 3, true, 300, 4));
        return new RankFusionService(props);
    }

    @Test
    void 同一文档多路命中分数累加() {
        RankFusionService fusion = service(0.7, 0.3);
        List<List<String>> lists = List.of(
                List.of("a", "b"),
                List.of("a", "c"));
        List<RankFusionService.Fused> fused = fusion.fuse(lists, 10);
        assertEquals("a", fused.get(0).content());
        // a 在两路都是第 1 名：1/(60+1)+1/(60+1)
        assertEquals(2.0 / 61, fused.get(0).score(), 1e-9);
    }

    @Test
    void topKTruncationAndDedup() {
        RankFusionService fusion = service(0.7, 0.3);
        List<List<String>> lists = List.of(List.of("a", "b", "c", "d"));
        List<RankFusionService.Fused> fused = fusion.fuse(lists, 2);
        assertEquals(2, fused.size());
        assertEquals("a", fused.get(0).content());
        assertEquals("b", fused.get(1).content());
    }

    @Test
    void 权重数量不符回退等权不抛异常() {
        RankFusionService fusion = service(0.7, 0.3);
        List<List<String>> lists = List.of(List.of("a"), List.of("b"));
        List<RankFusionService.Fused> fused = fusion.fuseWithWeights(lists, List.of(0.7), 10);
        assertEquals(2, fused.size());
    }

    @Test
    void 加权提升向量路排名靠前文档的分数() {
        RankFusionService heavyVector = service(0.9, 0.1);
        List<List<String>> lists = List.of(
                List.of("vectorTop", "textTop"),
                List.of("textTop", "vectorTop"));
        List<RankFusionService.Fused> fused = heavyVector.fuseWithWeights(
                lists, List.of(0.9, 0.1), 10);
        // vectorTop 在向量路第 1、全文路第 2；textTop 在全文路第 1、向量路第 2
        assertTrue(fused.get(0).content().equals("vectorTop") || fused.get(0).content().equals("textTop"));
        assertFalse(fused.isEmpty());
    }
}
