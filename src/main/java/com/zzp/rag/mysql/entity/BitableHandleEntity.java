package com.zzp.rag.mysql.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 飞书多维表格句柄（kind → 应用/表；「存入表格」跨重启复用同一应用）。
 */
@Data
@TableName("bitable_handle")
public class BitableHandleEntity {
    @TableId
    private String kind;
    private String appToken;
    private String tableId;
    private LocalDateTime updatedAt;
}
