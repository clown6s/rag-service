package com.zzp.rag.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzp.rag.mysql.entity.AlertEventEntity;

/**
 * 告警事件 Mapper（窗口统计 SQL 在 AuditEventMapper——查的是审计表）。
 */
public interface AlertEventMapper extends BaseMapper<AlertEventEntity> {
}
