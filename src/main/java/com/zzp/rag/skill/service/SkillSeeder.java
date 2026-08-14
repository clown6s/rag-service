package com.zzp.rag.skill.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zzp.rag.mysql.MysqlStatus;
import com.zzp.rag.skill.entity.SkillEntity;
import com.zzp.rag.skill.mapper.SkillMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 技能种子数据：启动时幂等写入两条内置技能（注册中心可见性；内置工具装配不依赖 DB）。
 * MySQL 不可用则跳过（问答行为由 BuiltinSkillFallback 兜底，无影响）；
 * 下次启动重试补齐。
 */
@Slf4j
@Component
@Order(20)   // 在 MysqlSchemaInitializer(@Order(10)) 之后执行，保证表已建
public class SkillSeeder implements ApplicationRunner {

    private final SkillMapper skillMapper;
    private final MysqlStatus status;

    @Value("${rag.mysql.enabled:true}")
    private boolean enabled;

    public SkillSeeder(SkillMapper skillMapper, MysqlStatus status) {
        this.skillMapper = skillMapper;
        this.status = status;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || !status.isAvailable()) {
            log.info("[技能] 种子数据跳过（业务库不可用，内置工具走兜底装配）");
            return;
        }
        try {
            seed(BuiltinSkillFallback.CODE_SEARCH_KB, "知识库检索",
                    "检索知识库（混合检索+重排+图遍历全链路），Agent 回答知识性/事实性问题的底座技能。",
                    "# 知识库检索\n\n平台内置技能：检索知识库文档片段。\n\n工具：searchKnowledgeBase（改写→混合检索→重排→图遍历）");
            seed(BuiltinSkillFallback.CODE_ENTITY_GRAPH, "知识图谱查询",
                    "查询知识图谱（实体关系、多跳关联），实体关系类问题的底座技能。",
                    "# 知识图谱查询\n\n平台内置技能：从用户问题提取实体做 N 跳图遍历。\n\n工具：queryEntityGraph");
            log.info("[技能] 种子数据完成（2 个内置技能）");
        } catch (Exception e) {
            // 种子失败只告警，不置业务库不可用（内置工具走兜底装配，无影响）
            log.warn("[技能] 种子数据失败，跳过: {}", e.getMessage());
        }
    }

    private void seed(String code, String name, String description, String skillMd) {
        Long exists = skillMapper.selectCount(
                new QueryWrapper<SkillEntity>().eq("code", code));
        if (exists != null && exists > 0) {
            return;
        }
        SkillEntity entity = new SkillEntity();
        entity.setCode(code);
        entity.setName(name);
        entity.setDescription(description);
        entity.setSkillMd(skillMd);
        entity.setToolType("builtin");
        entity.setStatus(1);
        skillMapper.insert(entity);
    }
}
