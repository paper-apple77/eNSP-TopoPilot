package com.topo.interceptor;

import com.topo.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 认证拦截器
 *
 * 每个请求进来时执行 preHandle，做三件事：
 * 1. 从 Authorization 头提取 Bearer token
 * 2. 检查 Redis 黑名单（已登出的 token 不能复用）
 * 3. 校验 token 有效期
 *
 * 通过后把 userId 写入 request.setAttribute，Controller 里直接拿。
 *
 * 放行路径在 WebConfig 里配置：
 *   /api/user/login、/api/user/register 不拦截
 *   /api/** 其他全部拦截
 */
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        // 浏览器会在跨域请求前先发 OPTIONS 预检，直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 提取 token：优先 Authorization header，兜底 query 参数（EventSource 不支持自定义 header）
        String token = null;
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else {
            token = request.getParameter("token");
        }
        if (token == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write("{\"code\":401,\"message\":\"未登录\"}");
            return false;
        }

        // 检查是否已登出（token 在黑名单里）
        String blacklisted = redisTemplate.opsForValue().get("blacklist:" + token);
        if (blacklisted != null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write("{\"code\":401,\"message\":\"token 已失效\"}");
            return false;
        }

        // 校验过期
        if (jwtUtil.isExpired(token)) {
            response.setStatus(401);
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write("{\"code\":401,\"message\":\"token 已过期\"}");
            return false;
        }

        // 解析出用户 ID，存入 request 供 Controller 使用
        Long userId = jwtUtil.extractUserId(token);
        request.setAttribute("userId", userId);
        return true;
    }
}
