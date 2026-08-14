package com.zzp.rag.mysql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数字员工配置表实体（阶段 4 接入运行时：systemPrompt/knowledgeScope 动态装配 AiServices）
 */
@Data
@TableName("digital_employee")
public class DigitalEmployeeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String avatar;
    private String description;
    private String systemPrompt;

    /** 'all' 或 JSON 数组字符串，如 ["docId1","docId2"]（阶段 4 用于检索元数据过滤） */
    private String knowledgeScope;

    /** 1 启用 0 停用 */
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
