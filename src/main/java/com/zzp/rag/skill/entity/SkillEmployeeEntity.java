package com.zzp.rag.skill.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 数字员工-技能授权关联表实体（employee_id + skill_id 联合主键） */
@Data
@TableName("skill_employee")
public class SkillEmployeeEntity {

    private Long employeeId;
    private Long skillId;
    private LocalDateTime createdAt;
}
