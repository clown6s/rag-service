package com.zzp.rag.mysql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** SOP 流程定义实体 */
@Data
@TableName("sop")
public class SopEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;
    private String name;
    private String keywords;
    private String stepsJson;
    private Integer enabled;
    private Integer builtin;
    private LocalDateTime updatedAt;
}
