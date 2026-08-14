package com.zzp.rag.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzp.rag.mysql.entity.AuditEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface AuditEventMapper extends BaseMapper<AuditEventEntity> {

    /**
     * 批量幂等 upsert：trace_id 唯一键兜底重复批次（崩溃重试/重启重扫天然无害）。
     * created_at 走 DB 默认值，不参与 upsert 更新。
     */
    @Insert("""
            <script>
            INSERT INTO audit_event (trace_id, `timestamp`, `user`, ip, endpoint, category, query,
              history_count, sources, retrieval_ms, rerank_degraded, graph_context_chars,
              answer_preview, answer_length, total_ms, status, error) VALUES
            <foreach collection="list" item="e" separator=",">
              (#{e.traceId}, #{e.timestamp}, #{e.user}, #{e.ip}, #{e.endpoint}, #{e.category}, #{e.query},
               #{e.historyCount}, #{e.sources}, #{e.retrievalMs}, #{e.rerankDegraded}, #{e.graphContextChars},
               #{e.answerPreview}, #{e.answerLength}, #{e.totalMs}, #{e.status}, #{e.error})
            </foreach>
            ON DUPLICATE KEY UPDATE
              query = VALUES(query), sources = VALUES(sources), retrieval_ms = VALUES(retrieval_ms),
              rerank_degraded = VALUES(rerank_degraded), graph_context_chars = VALUES(graph_context_chars),
              answer_preview = VALUES(answer_preview), answer_length = VALUES(answer_length),
              total_ms = VALUES(total_ms), status = VALUES(status), error = VALUES(error)
            </script>
            """)
    int batchUpsert(@Param("list") List<AuditEventEntity> list);

    /** 今日概览（看板统计卡） */
    @Select("""
            SELECT COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS success,
                   COALESCE(AVG(total_ms), 0) AS avg_ms
            FROM audit_event
            WHERE created_at >= CURDATE()
            """)
    Map<String, Object> todayOverview();

    /** 近 24 小时分布（看板柱状图，按整点分组） */
    @Select("""
            SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:00') AS hour,
                   COUNT(*) AS cnt,
                   COALESCE(SUM(CASE WHEN status != 'SUCCESS' THEN 1 ELSE 0 END), 0) AS err_cnt,
                   COALESCE(AVG(total_ms), 0) AS avg_ms
            FROM audit_event
            WHERE created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
            GROUP BY DATE_FORMAT(created_at, '%Y-%m-%d %H:00')
            ORDER BY hour
            """)
    List<Map<String, Object>> hourlyDistribution();
}
