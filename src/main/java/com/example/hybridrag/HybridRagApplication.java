package com.example.hybridrag;

import com.example.hybridrag.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 教学版混合检索工程入口。
 * 本地 H2 内存库演示两路召回 + RRF 融合 + 可选重排；生产形态见 README 第 7 节替换清单。
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class HybridRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(HybridRagApplication.class, args);
    }
}
