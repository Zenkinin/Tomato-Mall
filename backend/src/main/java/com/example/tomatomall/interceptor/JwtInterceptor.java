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

        // 1. 放行 OPTIONS (这个必须留着，因为CORS预检请求可能不会带Token)
        if ("OPTIONS".equals(method)) {
            return true;
        }

        // 3. 尝试解析 Token (核心逻辑)
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

        // 4. 弱校验白名单 (半白名单)
        // 这些接口没有在 WebMvcConfig 排除，因为它们“最好有Token，没有也行”
        // 比如：检查支付状态，或者商品详情页
        if (uri.matches("/api/orders/\\d+/check-payment")) {
            return true; // 放行 (如果有Token，上面第3步已经解析了)
        }

        // 5. 强校验 (剩下的所有接口，必须有 Token)
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