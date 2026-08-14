-- ============================================================
-- 业务库建表脚本（启动时由 MysqlSchemaInitializer 幂等执行）
-- 每张表 CREATE TABLE IF NOT EXISTS，可反复重跑
-- 注意：timestamp / user 是 MySQL 保留字，一律反引号
-- ============================================================

CREATE TABLE IF NOT EXISTS audit_event (
  id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  trace_id            VARCHAR(64)  NOT NULL COMMENT '全链路ID，幂等键',
  `timestamp`         VARCHAR(32)  NOT NULL COMMENT 'ISO-8601 原样存储（1:1 映射 JSONL）',
  `user`              VARCHAR(128) NOT NULL DEFAULT 'anonymous',
  ip                  VARCHAR(64)  NULL,
  endpoint            VARCHAR(32)  NULL,
  category            VARCHAR(64)  NULL,
  query               TEXT         NULL,
  history_count       INT          NOT NULL DEFAULT 0,
  sources             JSON         NULL,
  retrieval_ms        BIGINT       NOT NULL DEFAULT 0,
  rerank_degraded     TINYINT(1)   NOT NULL DEFAULT 0,
  graph_context_chars INT          NOT NULL DEFAULT 0,
  answer_preview      VARCHAR(1000) NULL,
  answer_length       INT          NOT NULL DEFAULT 0,
  total_ms            BIGINT       NOT NULL DEFAULT 0,
  status              VARCHAR(32)  NULL,
  error               VARCHAR(1000) NULL,
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_trace_id (trace_id),
  KEY idx_created_at (created_at),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 问答审计事件（JSONL 聚合入库）';

CREATE TABLE IF NOT EXISTS digital_employee (
  id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name            VARCHAR(64)  NOT NULL COMMENT '唯一',
  avatar          VARCHAR(16)  NOT NULL DEFAULT '🤖',
  description     VARCHAR(500) NULL,
  system_prompt   TEXT         NULL,
  knowledge_scope VARCHAR(2000) NOT NULL DEFAULT 'all' COMMENT "'all' 或 JSON 数组字符串，如 [\"docId1\"]",
  status          TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数字员工配置（阶段4接入运行时）';
