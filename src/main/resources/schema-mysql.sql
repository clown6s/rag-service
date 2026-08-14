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


-- ============================================================
-- 阶段3：技能注册中心
-- ============================================================

CREATE TABLE IF NOT EXISTS skill (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  code          VARCHAR(64)   NOT NULL COMMENT '技能编码，全局唯一（builtin_search_kb / builtin_entity_graph / demo_mcp）',
  name          VARCHAR(128)  NOT NULL COMMENT '技能名称（管理端展示）',
  description   VARCHAR(1000) NULL COMMENT '技能说明',
  skill_md      MEDIUMTEXT    NULL COMMENT 'SKILL.md 内容（本阶段存 DB 字段，不做文件上传）',
  tool_type     VARCHAR(16)   NOT NULL COMMENT 'builtin | mcp',
  mcp_config    JSON          NULL COMMENT 'MCP 连接配置，仅 tool_type=mcp 使用：{baseUrl,sseEndpoint,requestTimeoutSeconds,initializationTimeoutSeconds,enabled,retryCooldownSeconds}',
  tool_names    VARCHAR(1000) NULL COMMENT '暴露给 LLM 的工具名白名单，逗号分隔；NULL/空=该 MCP 全部工具',
  status        TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '1启用 0停用（注册中心级开关，停用=整体不装配）',
  created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_code (code),
  KEY idx_tool_type (tool_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='技能注册中心';

CREATE TABLE IF NOT EXISTS skill_employee (
  employee_id BIGINT UNSIGNED NOT NULL COMMENT '数字员工 id',
  skill_id    BIGINT UNSIGNED NOT NULL COMMENT '技能 id',
  created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (employee_id, skill_id),
  KEY idx_skill_id (skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数字员工-技能授权';
