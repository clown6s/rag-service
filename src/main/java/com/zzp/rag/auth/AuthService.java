package com.zzp.rag.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zzp.rag.mysql.MysqlStatus;
import com.zzp.rag.mysql.entity.SysUserEntity;
import com.zzp.rag.mysql.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后台统一身份（最小闭环）：用户名 + SHA-256 加盐密码 + 内存 token 会话。
 * <p>
 * 内网单实例部署，token 放内存（12h 过期，重启即失效需重登）——不引 Spring Security
 * 与 Redis 会话依赖，合规审计的最小可行身份。软依赖：MySQL 不可用时登录失败（校验走 DB），
 * 已登录会话不受影响（token 在内存）。
 */
@Slf4j
@Service
public class AuthService implements ApplicationRunner {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    /** 初始管理员：sys_user 空表时播种（首次登录后建议立即改密） */
    @Value("${rag.auth.initial-admin:admin}")
    private String initialAdmin;

    @Value("${rag.auth.initial-password:Admin@2026}")
    private String initialPassword;

    /** token 会话（token → 用户快照），12h TTL */
    private record Session(long userId, String username, String role, long expireAt) {}

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private static final long SESSION_TTL_MS = 12 * 3600_000L;

    private final SysUserMapper mapper;
    private final MysqlStatus status;
    private final SecureRandom random = new SecureRandom();

    public AuthService(SysUserMapper mapper, MysqlStatus status) {
        this.mapper = mapper;
        this.status = status;
    }

    /** 启动播种：无任何账号时创建初始管理员。
     *  ApplicationRunner（@Order 隐含默认，晚于 MysqlSchemaInitializer 的建表 runner）——
     *  历史坑：@PostConstruct 早于建表执行，selectCount 直接抛"表不存在"。 */
    @Override
    public void run(ApplicationArguments args) {
        if (!status.isAvailable()) {
            log.warn("[身份] 业务库不可用，跳过初始管理员播种（恢复后重启补种）");
            return;
        }
        try {
            Long count = mapper.selectCount(new QueryWrapper<>());
            if (count == null || count == 0) {
                createUser(initialAdmin, initialPassword, "系统管理员", ROLE_ADMIN);
                log.warn("[身份] 已创建初始管理员 {}（初始密码 {}，首次登录请立即修改）",
                        initialAdmin, initialPassword);
            }
        } catch (Exception e) {
            log.warn("[身份] 初始管理员播种失败: {}", e.getMessage());
        }
    }

    public record AuthUser(long id, String username, String role) {}

    // ==================== 登录/会话 ====================

    /** 登录：校验用户名密码，成功返回 token（失败 null） */
    public String login(String username, String password) {
        if (!status.isAvailable() || username == null || password == null) {
            return null;
        }
        try {
            SysUserEntity user = mapper.selectOne(
                    new QueryWrapper<SysUserEntity>().eq("username", username.strip()));
            if (user == null || user.getStatus() == null || user.getStatus() != 1) {
                return null;
            }
            if (!hash(password, user.getSalt()).equals(user.getPasswordHash())) {
                return null;
            }
            String token = UUID.randomUUID().toString().replace("-", "");
            sessions.put(token, new Session(user.getId(), user.getUsername(),
                    user.getRole(), System.currentTimeMillis() + SESSION_TTL_MS));
            user.setLastLoginAt(LocalDateTime.now());
            mapper.updateById(user);
            log.info("[身份] 登录成功 user={} role={}", username, user.getRole());
            return token;
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[身份] 登录失败: {}", e.getMessage());
            return null;
        }
    }

    /** 校验 token 返回用户（无效/过期 null） */
    public AuthUser verify(String token) {
        if (token == null) {
            return null;
        }
        Session s = sessions.get(token);
        if (s == null) {
            return null;
        }
        if (System.currentTimeMillis() > s.expireAt) {
            sessions.remove(token);
            return null;
        }
        return new AuthUser(s.userId, s.username, s.role);
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    // ==================== 改密 ====================

    /** 修改密码（旧密码校验 + 新密码 8 位起） */
    public String changePassword(String token, String oldPassword, String newPassword) {
        AuthUser user = verify(token);
        if (user == null || newPassword == null || newPassword.length() < 8) {
            return "token 无效或新密码不足 8 位";
        }
        if (!status.isAvailable()) {
            return "业务库不可用，请稍后再试";
        }
        try {
            SysUserEntity entity = mapper.selectById(user.id());
            if (entity == null || !hash(oldPassword, entity.getSalt()).equals(entity.getPasswordHash())) {
                return "旧密码错误";
            }
            String salt = newSalt();
            entity.setSalt(salt);
            entity.setPasswordHash(hash(newPassword, salt));
            mapper.updateById(entity);
            log.info("[身份] 修改密码成功 user={}", user.username());
            return null;
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            return "修改失败，请稍后再试";
        }
    }

    // ==================== 内部 ====================

    private void createUser(String username, String password, String displayName, String role) {
        String salt = newSalt();
        SysUserEntity e = new SysUserEntity();
        e.setUsername(username);
        e.setSalt(salt);
        e.setPasswordHash(hash(password, salt));
        e.setDisplayName(displayName);
        e.setRole(role);
        e.setStatus(1);
        mapper.insert(e);
    }

    private String newSalt() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String hash(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    md.digest((salt + password).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
