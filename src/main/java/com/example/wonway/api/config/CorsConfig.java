package com.example.wonway.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 프런트엔드 개발 서버(다른 포트)에서 상담 API를 호출할 수 있도록 CORS를 허용합니다.
 * 허용 범위는 /api/** 경로와 실제로 쓰는 메서드로 한정합니다.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    // 프런트 로컬 개발 서버 주소. 배포 주소가 정해지면 함께 추가한다.
    private static final String[] ALLOWED_ORIGINS = {
            "http://localhost:5173",
            "http://127.0.0.1:5173"
    };

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(ALLOWED_ORIGINS)
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type")
                .maxAge(3600);
    }
}
