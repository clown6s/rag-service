package com.zzp.rag.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zzp.rag.mysql.MysqlStatus;
import com.zzp.rag.mysql.entity.SysUserEntity;
import com.zzp.rag.mysql.mapper.SysUserMapper;
import com.zzp.rag.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 后台身份 API（统一身份最小闭环）。
 * <p>
 * POST /api/rag/admin/auth/login     — 登录（免鉴权）→ token
 * GET  /api/rag/admin/auth/init      — 是否已初始化（前端据此决定显示登录页还是初始化提示）
 * GET  /api/rag/admin/auth/me        — 当前登录用户
 * POST /api/rag/admin/auth/logout    — 退出
 * POST /api/rag/admin/auth/password  — 修改密码（旧密码校验）
 * GET  /api/rag/admin/auth/users     — 账号列表（ADMIN）
 * POST /api/rag/admin/auth/users     — 创建账号（ADMIN；USER 角色默认只读）
 */
@Slf4j
@RestController
@RequestMapping("/api/rag/admin/auth")
public class AuthController {

    private final AuthService authService;
    private final SysUserMapper mapper;
    private final MysqlStatus status;

    public AuthController(AuthService authService, SysUserMapper mapper, MysqlStatus status) {
        this.authService = authService;
        this.mapper = mapper;
        this.status = status;
    }

    public record UserView(long id, String username, String displayName, String role, int status,
                           String createdAt, String lastLoginAt) {}

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String token = authService.login(body.get("username"), body.get("password"));
        if (token == null) {
            return ApiResponse.fail("用户名或密码错误，或业务库不可用");
        }
        AuthService.AuthUser user = authService.verify(token);
        return ApiResponse.ok(Map.of(
                "token", token,
                "username", user.username(),
                "role", user.role()));
    }

    @GetMapping("/init")
    public ApiResponse<Map<String, Boolean>> init() {
        try {
            Long count = mapper.selectCount(new QueryWrapper<>());
            return ApiResponse.ok(Map.of("initialized", count != null && count > 0));
        } catch (Exception e) {
            return ApiResponse.ok(Map.of("initialized", false));
        }
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
        AuthService.AuthUser user = (AuthService.AuthUser) request.getAttribute(AuthInterceptor.AUTH_USER_ATTR);
        if (user == null) {
            return ApiResponse.fail("未登录");
        }
        return ApiResponse.ok(Map.of("username", user.username(), "role", user.role()));
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Boolean>> logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth != null && auth.startsWith("Bearer ")) {
            authService.logout(auth.substring(7).strip());
        }
        return ApiResponse.ok(Map.of("loggedOut", true));
    }

    @PostMapping("/password")
    public ApiResponse<Map<String, Boolean>> changePassword(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody Map<String, String> body) {
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).strip() : null;
        String err = authService.changePassword(token, body.get("oldPassword"), body.get("newPassword"));
        if (err != null) {
            return ApiResponse.fail(err);
        }
        return ApiResponse.ok(Map.of("changed", true));
    }

    @GetMapping("/users")
    public ApiResponse<List<UserView>> users() {
        if (!status.isAvailable()) {
            return ApiResponse.fail("业务库不可用");
        }
        try {
            return ApiResponse.ok(mapper.selectList(new QueryWrapper<SysUserEntity>().orderByAsc("id"))
                    .stream().map(this::toView).toList());
        } catch (Exception e) {
            return ApiResponse.fail("查询失败");
        }
    }

    @PostMapping("/users")
    public ApiResponse<UserView> createUser(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String displayName = body.getOrDefault("displayName", username);
        String role = "USER".equals(body.get("role")) ? "USER" : "USER";
        if (username == null || !username.matches("^[a-zA-Z0-9_]{3,32}$")) {
            return ApiResponse.fail("用户名需 3-32 位字母数字下划线");
        }
        if (password == null || password.length() < 8) {
            return ApiResponse.fail("密码至少 8 位");
        }
        if (!status.isAvailable()) {
            return ApiResponse.fail("业务库不可用");
        }
        try {
            Long dup = mapper.selectCount(new QueryWrapper<SysUserEntity>().eq("username", username));
            if (dup != null && dup > 0) {
                return ApiResponse.fail("用户名已存在");
            }
            SysUserEntity e = new SysUserEntity();
            e.setUsername(username);
            e.setSalt(java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            e.setPasswordHash(hash(e.getSalt(), password));
            e.setDisplayName(displayName);
            e.setRole(role);
            e.setStatus(1);
            mapper.insert(e);
            log.info("[身份] 创建账号 {} role={}", username, role);
            return ApiResponse.ok(toView(mapper.selectById(e.getId())));
        } catch (Exception ex) {
            status.markUnavailable(ex.getMessage());
            return ApiResponse.fail("创建失败");
        }
    }

    private UserView toView(SysUserEntity e) {
        DateTimeFormatter ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return new UserView(e.getId(), e.getUsername(), e.getDisplayName(),
                e.getRole() == null ? "USER" : e.getRole(),
                e.getStatus() == null ? 1 : e.getStatus(),
                e.getCreatedAt() != null ? e.getCreatedAt().format(ts) : null,
                e.getLastLoginAt() != null ? e.getLastLoginAt().format(ts) : null);
    }

    private String hash(String salt, String password) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    md.digest((salt + password).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
