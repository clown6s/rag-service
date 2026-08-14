package com.zzp.rag.mysql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警事件表实体（规则引擎输出，一规则一行：重触发原地更新，恢复置 RESOLVED）。
 */
@Data
@TableName("alert_event")
public class AlertEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ruleCode;
    private String level;
    private String title;
    private String detail;
    private String status;

    private LocalDateTime firstAt;
    private LocalDateTime lastAt;
    private Integer count;

    private String ackBy;
    private LocalDateTime ackAt;
}
