package com.zzp.rag.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzp.rag.mysql.entity.FeedbackAdoptEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 上下文飞轮采纳记录 Mapper。
 */
public interface FeedbackAdoptMapper extends BaseMapper<FeedbackAdoptEntity> {

    /** 近 N 天逐日采纳量（看板采纳率曲线：采纳量 / 问答量） */
    @Select("""
            SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS day, COUNT(*) AS cnt
            FROM feedback_adopt
            WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
            GROUP BY DATE_FORMAT(created_at, '%Y-%m-%d')
            ORDER BY day
            """)
    List<Map<String, Object>> adoptionTrend(@Param("days") int days);
}
