package com.example.tomatomall.interceptor;

import com.example.tomatomall.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.tomatomall.vo.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
@Slf4j
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        String method = request.getMethod();

        // 1. 放行 OPTIONS 预检请求 (跨域必备)
        if ("OPTIONS".equals(method)) {
            return true;
        }

        // 2. 绝对白名单 (完全不需要 Token，甚至不需要解析用户信息)
        // 包括：支付宝回调、登录、注册
        if (uri.startsWith("/api/orders/notify") ||
                "/api/accounts/login".equals(uri) ||
                ("/api/accounts".equals(uri) && "POST".equalsIgnoreCase(method))) {
            return true;
        }

        // 3. 尝试解析 Token (无论是否强制需要，都先解析出来备用)
        // 统一处理，避免重复代码
        String token = request.getHeader("token");
        if (token == null || token.isEmpty()) {
            // 兼容一下 Authorization: Bearer xxx 格式（可选）
            String bearer = request.getHeader("Authorization");
            if (bearer != null && bearer.startsWith("Bearer ")) {
                token = bearer.substring(7);
            }
        }

        boolean isTokenValid = (token != null && jwtUtil.validateToken(token));
        if (isTokenValid) {
            // Token 有效，提取用户信息放入 Request
            Integer userId = jwtUtil.getUserIdFromToken(token);
            String username = jwtUtil.extractUsername(token);
            request.setAttribute("userId", userId);
            request.setAttribute("username", username);
            log.info("用户已认证 - ID: {}, Name: {}", userId, username);
        }

        // 4. 弱校验白名单 (不需要 Token 也能访问，但如果有 Token 更好)
        // 比如：商品详情页、首页推荐、检查支付状态(视业务而定)
        if (uri.matches("/api/orders/\\d+/check-payment") ||
                uri.contains("/payment-success")) {
            return true; // 放行，Controller 里自己判断 userId 是否为 null
        }

        // 5. 强校验 (必须有有效 Token 才能通过)
        // 支付接口(/pay) 应该放在这里，强制要求登录
        if (isTokenValid) {
            return true;
        }

        // 6. 验证失败
        log.warn("拦截未授权访问: {}", uri);
        handleUnauthorized(response);
        return false;
    }

    private void handleUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        ObjectMapper mapper = new ObjectMapper();
        response.getWriter().write(mapper.writeValueAsString(Response.buildFailure("未授权", "401")));
    }

    private String extractToken(HttpServletRequest request) {
        // 从Authorization头获取
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        
        // 尝试从请求参数获取
        String token = request.getParameter("token");
        if (token != null && !token.isEmpty()) {
            return token;
        }
        
        return null;
    }

    private Integer extractUserIdFromToken(String token) {
        // 实现从token获取userId的逻辑
        // 使用您现有的JwtTokenUtil或其他方式
        try {
            return jwtUtil.getUserIdFromToken(token);
        } catch (Exception e) {
            log.error("从token中提取userId失败: {}", e.getMessage());
            return null;
        }
    }
}