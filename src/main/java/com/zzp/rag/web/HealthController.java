package com.zzp.rag.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 进程健康端点（看门狗探活用）。
 * 不依赖 actuator，不带业务依赖检查——JVM 能响应即 UP；
 * 依赖（MySQL/Milvus/Neo4j/Redis）均为软依赖，挂了不判进程死亡（有各自降级路径）。
 */
@RestController
public class HealthController {

    @GetMapping("/healthz")
    public Map<String, Object> healthz() {
        return Map.of("status", "UP", "service", "rag-service", "time",
                LocalDateTime.now().toString());
    }
}
