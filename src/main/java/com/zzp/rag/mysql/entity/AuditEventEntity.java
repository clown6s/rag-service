package com.zzp.rag.mysql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计事件表实体（1:1 映射 AuditEvent record；JSONL 聚合入库）。
 * timestamp / user 是 MySQL 保留字，用反引号包裹。
 * sources 为 JSON 列，实体存 String（聚合器用 ObjectMapper 序列化）。
 */
@Data
@TableName("audit_event")
public class AuditEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;

    @TableField("`timestamp`")
    private String timestamp;

    @TableField("`user`")
    private String user;

    private String ip;
    private String endpoint;
    private String category;
    private String query;
    private Integer historyCount;
    private String sources;
    private Long retrievalMs;
    private Boolean rerankDegraded;
    private Integer graphContextChars;
    private String answerPreview;
    private Integer answerLength;
    private Long totalMs;
    private String status;
    private String error;

    /** 入库时间（DB 默认 CURRENT_TIMESTAMP，聚合时置空） */
    private LocalDateTime createdAt;
}
