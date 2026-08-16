package com.zzp.rag.mysql.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 飞书通讯录员工（统一身份：open_id → 姓名/职位/角色）。
 * 角色：员工 / 总监 / 总经理；role_manual=1 时同步不覆盖人工指定角色。
 */
@Data
@TableName("feishu_employee")
public class FeishuEmployeeEntity {
    @TableId
    private String openId;
    private String name;
    private String jobTitle;
    private String deptIds;
    private String role;
    private Integer roleManual;
    private LocalDateTime syncedAt;
    private LocalDateTime createdAt;
}
