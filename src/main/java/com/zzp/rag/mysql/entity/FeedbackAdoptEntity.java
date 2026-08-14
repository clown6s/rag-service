package com.zzp.rag.mysql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上下文飞轮采纳记录（防重复采纳；query_hash 唯一）。
 */
@Data
@TableName("feedback_adopt")
public class FeedbackAdoptEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String queryHash;
    private String query;
    private String answer;
    private String source;
    private String adoptedBy;
    private LocalDateTime createdAt;
}
