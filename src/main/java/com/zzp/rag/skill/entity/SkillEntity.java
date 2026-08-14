package com.zzp.rag.skill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 技能注册中心表实体。
 * tool_type: builtin（存量 @Tool 反射执行）| mcp（远程 MCP 服务桥接）
 * mcp_config 存 JSON 字符串；tool_names 逗号分隔白名单。
 */
@Data
@TableName("skill")
public class SkillEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;
    private String name;
    private String description;
    private String skillMd;
    private String toolType;
    private String mcpConfig;
    private String toolNames;
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
