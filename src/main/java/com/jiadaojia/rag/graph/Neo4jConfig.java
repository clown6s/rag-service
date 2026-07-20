package com.jiadaojia.rag.graph;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Neo4j 图数据库连接配置。
 * connect 时做一次连通性校验，失败只打 warn 不阻断启动，
 * 实际查询时才报错，保证 Neo4j 宕机不影响向量检索主链路。
 */
@Slf4j
@Configuration
public class Neo4jConfig {

    @Value("${neo4j.uri}")
    private String uri;

    @Value("${neo4j.authentication.username}")
    private String username;

    @Value("${neo4j.authentication.password}")
    private String password;

    @Bean
    public Driver neo4jDriver() {
        Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
        try {
            driver.verifyConnectivity();
            log.info("[Neo4j] 连接成功 uri={}", uri);
        } catch (Exception e) {
            log.warn("[Neo4j] 连通性校验失败（将在首次查询时报错） uri={}: {}", uri, e.getMessage());
        }
        return driver;
    }
}
