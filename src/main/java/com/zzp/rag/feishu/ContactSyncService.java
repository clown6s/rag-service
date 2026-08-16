package com.zzp.rag.feishu;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zzp.rag.mysql.MysqlStatus;
import com.zzp.rag.mysql.entity.FeishuEmployeeEntity;
import com.zzp.rag.mysql.mapper.FeishuEmployeeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通讯录同步与统一身份解析。
 * <p>
 * - sync()：飞书通讯录全量拉取 → upsert feishu_employee（角色按职位自动判定，
 *   人工指定过的角色不覆盖）
 * - resolve(openId)：open_id → 角色（员工/总监/总经理；未同步过返回"员工"）
 * <p>
 * 角色判定：职位含 总经理/CEO/总裁 → 总经理；含 总监 → 总监；其余 → 员工。
 * 软依赖：业务库不可用/同步失败返回 null，对话按默认角色继续。
 */
@Slf4j
@Service
public class ContactSyncService {

    private final FeishuClient client;
    private final FeishuEmployeeMapper mapper;
    private final MysqlStatus status;

    /** open_id → 角色 内存缓存（60s，桥接每问调用） */
    private final ConcurrentHashMap<String, CacheEntry> roleCache = new ConcurrentHashMap<>();
    private record CacheEntry(String role, long loadedAt) {}

    /** 启动自动同步（飞书启用时）；权限未开通会告警并跳过 */
    @Value("${rag.feishu.contact-sync-on-start:true}")
    private boolean syncOnStart;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (syncOnStart) {
            new Thread(() -> {
                try {
                    Thread.sleep(5000);   // 等飞书 WS/token 就绪
                    sync();
                } catch (Exception ignored) {
                }
            }, "contact-sync").start();
        }
    }

    public ContactSyncService(FeishuClient client, FeishuEmployeeMapper mapper, MysqlStatus status) {
        this.client = client;
        this.mapper = mapper;
        this.status = status;
    }

    /** 全量同步（管理后台/定时触发）；返回同步人数，失败返回 -1 */
    public int sync() {
        if (!status.isAvailable()) {
            return -1;
        }
        try {
            List<FeishuClient.ContactUser> users = client.listContactUsers();
            if (users.isEmpty()) {
                log.warn("[通讯录] 同步拉取为空（权限未开通？）");
                return -1;
            }
            int upserted = 0;
            for (FeishuClient.ContactUser u : users) {
                FeishuEmployeeEntity e = mapper.selectById(u.openId());
                if (e == null) {
                    e = new FeishuEmployeeEntity();
                    e.setOpenId(u.openId());
                    e.setRoleManual(0);
                }
                e.setName(u.name());
                e.setJobTitle(u.jobTitle());
                e.setDeptIds(u.deptIds());
                if (e.getRoleManual() == null || e.getRoleManual() == 0) {
                    e.setRole(roleOf(u.jobTitle()));   // 人工指定过的角色不被覆盖
                }
                e.setSyncedAt(LocalDateTime.now());
                mapper.insertOrUpdate(e);
                upserted++;
            }
            roleCache.clear();
            log.info("[通讯录] 同步完成 {} 人", upserted);
            return upserted;
        } catch (Exception e) {
            log.warn("[通讯录] 同步失败: {}", e.getMessage());
            return -1;
        }
    }

    /** open_id → 角色（未同步/库不可用 → 员工） */
    public String resolveRole(String openId) {
        if (openId == null || openId.isBlank() || !status.isAvailable()) {
            return "员工";
        }
        CacheEntry cached = roleCache.get(openId);
        if (cached != null && System.currentTimeMillis() - cached.loadedAt() < 60_000) {
            return cached.role();
        }
        try {
            FeishuEmployeeEntity e = mapper.selectById(openId);
            String role = e != null && e.getRole() != null ? e.getRole() : "员工";
            roleCache.put(openId, new CacheEntry(role, System.currentTimeMillis()));
            return role;
        } catch (Exception ex) {
            return "员工";
        }
    }

    /** 人工指定角色（管理后台纠正用） */
    public boolean setRole(String openId, String role) {
        if (!status.isAvailable()) {
            return false;
        }
        try {
            FeishuEmployeeEntity e = mapper.selectById(openId);
            if (e == null) {
                return false;
            }
            e.setRole(role);
            e.setRoleManual(1);
            mapper.updateById(e);
            roleCache.remove(openId);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /** 职位 → 角色 */
    static String roleOf(String jobTitle) {
        if (jobTitle == null) {
            return "员工";
        }
        if (jobTitle.contains("总经理") || jobTitle.contains("CEO") || jobTitle.contains("总裁")) {
            return "总经理";
        }
        if (jobTitle.contains("总监")) {
            return "总监";
        }
        return "员工";
    }
}
