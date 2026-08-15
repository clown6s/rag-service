package com.zzp.rag.mysql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 文档级访问控制实体 */
@Data
@TableName("doc_acl")
public class DocAclEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String docId;
    private String grants;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
