package com.zzp.rag.skill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzp.rag.skill.entity.SkillEmployeeEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SkillEmployeeMapper extends BaseMapper<SkillEmployeeEntity> {

    /** 员工已授权的技能 id 列表 */
    @Select("SELECT skill_id FROM skill_employee WHERE employee_id = #{employeeId}")
    List<Long> findSkillIdsByEmployee(@Param("employeeId") long employeeId);

    /** 某技能被授权的员工数（列表页展示） */
    @Select("SELECT COUNT(*) FROM skill_employee WHERE skill_id = #{skillId}")
    long countEmployeesBySkill(@Param("skillId") long skillId);

    /** 幂等授权（INSERT IGNORE，联合主键兜底） */
    @Insert("INSERT IGNORE INTO skill_employee (employee_id, skill_id) VALUES (#{employeeId}, #{skillId})")
    int grant(@Param("employeeId") long employeeId, @Param("skillId") long skillId);

    /** 移除员工全部授权（全量替换语义的前置步骤） */
    @Delete("DELETE FROM skill_employee WHERE employee_id = #{employeeId}")
    int revokeAll(@Param("employeeId") long employeeId);
}
