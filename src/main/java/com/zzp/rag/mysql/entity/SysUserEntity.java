package com.zzp.rag.mysql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 后台账号表实体（统一身份最小闭环：用户名 + 密码哈希 + 角色）。
 */
@Data
@TableName("sys_user")
public class SysUserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    private String passwordHash;
    private String salt;
    private String displayName;

    /** ADMIN / USER（USER 只读） */
    private String role;

    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;
}
