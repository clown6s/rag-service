package com.zzp.rag.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzp.rag.mysql.entity.AuditEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
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
              answer_preview, answer_length, total_ms, status, error, employee_id) VALUES
            <foreach collection="list" item="e" separator=",">
              (#{e.traceId}, #{e.timestamp}, #{e.user}, #{e.ip}, #{e.endpoint}, #{e.category}, #{e.query},
               #{e.historyCount}, #{e.sources}, #{e.retrievalMs}, #{e.rerankDegraded}, #{e.graphContextChars},
               #{e.answerPreview}, #{e.answerLength}, #{e.totalMs}, #{e.status}, #{e.error}, #{e.employeeId})
            </foreach>
            ON DUPLICATE KEY UPDATE
              query = VALUES(query), sources = VALUES(sources), retrieval_ms = VALUES(retrieval_ms),
              rerank_degraded = VALUES(rerank_degraded), graph_context_chars = VALUES(graph_context_chars),
              answer_preview = VALUES(answer_preview), answer_length = VALUES(answer_length),
              total_ms = VALUES(total_ms), status = VALUES(status), error = VALUES(error),
              employee_id = VALUES(employee_id)
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

    /** 告警引擎：窗口内非成功事件数（status 为 FAILED / GENERATION_FAILED / RETRIEVAL_FAILED 都算错） */
    @Select("SELECT COUNT(*) FROM audit_event WHERE created_at >= #{since} AND status <> 'SUCCESS'")
    long windowErrorCount(@Param("since") LocalDateTime since);

    /** 告警引擎：窗口内平均端到端耗时（毫秒；无数据返回 null） */
    @Select("SELECT AVG(total_ms) FROM audit_event WHERE created_at >= #{since}")
    Double windowAvgMs(@Param("since") LocalDateTime since);

    // ==================== 数据看板（运营闭环） ====================

    /** 近 N 天逐日趋势（问答量/成功率/未答上/飞书渠道/均耗时） */
    @Select("""
            SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS day,
                   COUNT(*) AS cnt,
                   COALESCE(SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS success,
                   COALESCE(SUM(CASE WHEN answer_preview LIKE '%暂无法回答%' THEN 1 ELSE 0 END), 0) AS no_answer,
                   COALESCE(SUM(CASE WHEN endpoint = 'feishu/chat' THEN 1 ELSE 0 END), 0) AS feishu,
                   COALESCE(AVG(total_ms), 0) AS avg_ms
            FROM audit_event
            WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
            GROUP BY DATE_FORMAT(created_at, '%Y-%m-%d')
            ORDER BY day
            """)
    List<Map<String, Object>> dailyTrend(@Param("days") int days);

    /** 近 N 天热门问题 Top-N */
    @Select("""
            SELECT query, COUNT(*) AS cnt
            FROM audit_event
            WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
              AND query IS NOT NULL AND query <> ''
            GROUP BY query
            ORDER BY cnt DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> topQueries(@Param("days") int days, @Param("limit") int limit);

    /** 近 N 天路由分布（category 为空归入"未知"） */
    @Select("""
            SELECT COALESCE(NULLIF(category, ''), '未知') AS route, COUNT(*) AS cnt
            FROM audit_event
            WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
            GROUP BY COALESCE(NULLIF(category, ''), '未知')
            ORDER BY cnt DESC
            """)
    List<Map<String, Object>> routeDistribution(@Param("days") int days);

    /** 近 N 天员工活跃度 Top-N（按 user 聚合问答量与均耗时） */
    @Select("""
            SELECT `user` AS user_name, COUNT(*) AS cnt, COALESCE(AVG(total_ms), 0) AS avg_ms
            FROM audit_event
            WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
              AND `user` IS NOT NULL AND `user` <> ''
            GROUP BY `user`
            ORDER BY cnt DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> employeeActivity(@Param("days") int days, @Param("limit") int limit);

    /** 近 N 天问答总量与未答上量（知识库健康度概览） */
    @Select("""
            SELECT COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN answer_preview LIKE '%暂无法回答%' THEN 1 ELSE 0 END), 0) AS unanswered
            FROM audit_event
            WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
            """)
    Map<String, Object> unansweredOverview(@Param("days") int days);

    /** 近 N 天未答上的问题及频次（健康度聚类原料） */
    @Select("""
            SELECT query, COUNT(*) AS cnt
            FROM audit_event
            WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
              AND answer_preview LIKE '%暂无法回答%'
              AND query IS NOT NULL AND query <> ''
            GROUP BY query
            ORDER BY cnt DESC
            LIMIT 300
            """)
    List<Map<String, Object>> unansweredQueries(@Param("days") int days);
}
