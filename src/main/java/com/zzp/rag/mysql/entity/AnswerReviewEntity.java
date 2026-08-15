package com.zzp.rag.mysql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 答案审核流实体 */
@Data
@TableName("answer_review")
public class AnswerReviewEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String query;
    private String answer;
    private String sources;
    private Long employeeId;
    private String user;
    private String status;
    private String reviewer;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
}
