package com.zzp.rag.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.audit.AuditEvent;
import com.zzp.rag.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理后台鉴权拦截（/api/rag/admin/**）：
 * 1. Bearer token 校验（AuthService 内存会话）
 * 2. 通过后把认证用户名写入 MDC（AuditContext.capture 优先读 MDC，
 *    审计链路无需感知请求包装即可拿到真实身份）
 * 3. RBAC：USER 角色只读（GET），写操作（POST/PUT/DELETE/PATCH）需 ADMIN
 * <p>
 * 白名单：/auth/login 与 /auth/init；对话/飞书/MCP 等非 admin 路径不受影响。
 */
@Slf4j
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String AUTH_USER_ATTR = "auth.user";
    public static final String AUTH_ROLE_ATTR = "auth.role";

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public AuthInterceptor(AuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI();
        if ("/api/rag/admin/auth/login".equals(path) || "/api/rag/admin/auth/init".equals(path)) {
            return true;   // 登录与初始账号查询免鉴权
        }
        String token = bearerToken(request);
        AuthService.AuthUser user = token != null ? authService.verify(token) : null;
        if (user == null) {
            writeJson(response, 401, "未登录或会话已过期，请重新登录");
            return false;
        }
        // RBAC：USER 只读——仅限管理路径的写操作；对话（/agent/**）任何登录用户可用
        String method = request.getMethod();
        boolean managementPath = path.startsWith("/api/rag/admin/");
        if (managementPath && !AuthService.ROLE_ADMIN.equals(user.role())
                && !HttpMethod.GET.matches(method) && !HttpMethod.OPTIONS.matches(method)) {
            writeJson(response, 403, "当前账号为只读角色，无此操作权限");
            return false;
        }
        request.setAttribute(AUTH_USER_ATTR, user.username());
        request.setAttribute(AUTH_ROLE_ATTR, user.role());
        MDC.put(AuditEvent.MDC_USER_KEY, user.username());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        MDC.remove(AuditEvent.MDC_USER_KEY);
    }

    private String bearerToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).strip();
        }
        return null;
    }

    private void writeJson(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(message)));
    }
}
