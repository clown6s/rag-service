package com.zzp.rag.graph;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Neo4j 图数据读写。
 * <p>
 * 写入：MERGE 保证幂等，重复摄入不会创建重复节点/关系。
 * 读取：从候选实体出发做 1-N 跳遍历，返回关联实体及关系路径。
 */
@Slf4j
@Component
public class GraphStore {

    private final Driver driver;

    public GraphStore(Driver driver) {
        this.driver = driver;
    }

    /**
     * 保存实体的实体和关系到 Neo4j
     * 并发 MERGE 同一实体节点会触发死锁（ForsetiClient DeadlockDetected），瞬时错误重试 3 次
     */
    public void save(EntityExtractor.GraphData data, String docId, int chunkIndex) {
        if (data == null || data.entities().isEmpty()) return;
        String chunkId = docId + "_" + chunkIndex;

        int maxAttempts = 3;
        for (int attempt = 1; ; attempt++) {
            try (Session session = driver.session()) {
                // 实体节点（null 守卫：模型可能输出 name:null，Map.of 不允许 null 值）
                for (var entity : data.entities()) {
                    if (entity == null || entity.name() == null || entity.name().isBlank()) {
                        continue;
                    }
                    session.run("""
                        MERGE (e:Entity {name: $name})
                        SET e.type = $type
                        FOREACH (_ IN CASE WHEN NOT $chunkId IN coalesce(e.chunkIds, []) THEN [1] ELSE [] END |
                            SET e.chunkIds = coalesce(e.chunkIds, []) + $chunkId
                        )
                        """,
                        Map.of("name", entity.name(),
                               "type", entity.type() == null ? "" : entity.type(),
                               "chunkId", chunkId));
                }

                // 关系边（null 守卫同上）
                if (data.relations() != null) {
                    for (var relation : data.relations()) {
                        if (relation == null || relation.subject() == null
                                || relation.object() == null || relation.relation() == null) {
                            continue;
                        }
                        session.run("""
                            MERGE (a:Entity {name: $subject})
                            MERGE (b:Entity {name: $object})
                            MERGE (a)-[r:RELATES {type: $type}]->(b)
                            """,
                            Map.of("subject", relation.subject(),
                                   "object", relation.object(),
                                   "type", relation.relation()));
                    }
                }
                return;   // 成功
            } catch (org.neo4j.driver.exceptions.TransientException e) {
                if (attempt >= maxAttempts) {
                    log.warn("[图存储] 写入失败(死锁重试耗尽) docId={} chunk={}: {}", docId, chunkIndex, e.getMessage());
                    return;
                }
                log.warn("[图存储] 死锁重试 {}/{} docId={} chunk={}", attempt, maxAttempts, docId, chunkIndex);
                try {
                    Thread.sleep(100L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (Exception e) {
                log.warn("[图存储] 写入失败 docId={} chunk={}: {}", docId, chunkIndex, e.getMessage());
                return;
            }
        }
    }

    /**
     * 图遍历：从实体名列表出发，N 跳查找关联实体
     */
    public TraversalResult traverse(List<String> entityNames, int maxHops) {
        if (entityNames == null || entityNames.isEmpty()) return TraversalResult.empty();

        try (Session session = driver.session()) {
            String cypher = """
                MATCH (e:Entity)
                WHERE e.name IN $names
                MATCH path = (e)-[:RELATES*1..%d]-(related:Entity)
                RETURN e.name AS source,
                       related.name AS target,
                       [r IN relationships(path) | r.type] AS relTypes,
                       COALESCE(related.chunkIds, []) AS chunkIds
                LIMIT 100
                """.formatted(maxHops);

            var result = session.run(cypher, Map.of("names", entityNames));

            List<PathResult> paths = new ArrayList<>();
            while (result.hasNext()) {
                Record record = result.next();
                String source = record.get("source").asString();
                String target = record.get("target").asString();
                List<String> relTypes = record.get("relTypes").asList(Value::asString);
                List<String> chunkIds = record.get("chunkIds").asList(Value::asString);
                paths.add(new PathResult(source, target, relTypes, chunkIds));
            }

            log.debug("[图遍历] 实体={} hops={} 路径={}条",
                    entityNames.size(), maxHops, paths.size());
            return new TraversalResult(paths);
        } catch (Exception e) {
            log.warn("[图遍历] 查询失败: {}", e.getMessage());
            return TraversalResult.empty();
        }
    }

    // ==================== 数据模型 ====================

    public record PathResult(String source, String target, List<String> relTypes, List<String> chunkIds) {}

    public record TraversalResult(List<PathResult> paths) {
        public static TraversalResult empty() { return new TraversalResult(List.of()); }

        public boolean isEmpty() { return paths.isEmpty(); }

        /** 格式化为 LLM 可读的文本 */
        public String format() {
            if (paths.isEmpty()) return "";
            var seen = new LinkedHashSet<String>();
            for (var path : paths) {
                String arrow = String.join(" → ", path.relTypes());
                seen.add("- %s --[%s]--> %s".formatted(path.source(), arrow, path.target()));
            }
            return seen.stream().collect(Collectors.joining("\n"));
        }
    }
}
