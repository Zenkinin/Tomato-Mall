package com.example.tomatomall.config;

import com.example.tomatomall.interceptor.JwtInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private JwtInterceptor jwtInterceptor;

    // 1. 配置拦截器 (原 WebMvcConfig 的内容)
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                // 这里只写最基础的排除，具体的白名单逻辑建议在 Interceptor 内部判断，维护起来更方便
                .excludePathPatterns("/api/accounts/login", "/api/accounts/register");
    }

    // 2. 配置跨域 (原 CorsConfig 的内容)
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // 注意：如果你的前端端口是 5173 (Vite默认)，这里记得加上
                .allowedOrigins("http://localhost:8080", "http://localhost:3000", "http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}