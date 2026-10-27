package com.example.wonway.policy.persistence;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** local/dev + 명시적 enabled=true에서만 실행. 리소스는 서버 설정으로 지정한다. */
@Component
@Profile({"local","dev"})
public class PolicyDataInitializer {
    private final PolicyImportService service;
    private final boolean enabled;
    private final Resource resource;
    public PolicyDataInitializer(PolicyImportService service,
            @Value("${policy.seed.enabled:false}") boolean enabled,
            @Value("${policy.seed.location:classpath:policy/policies.json}") Resource resource) {
        this.service=service; this.enabled=enabled; this.resource=resource;
    }
    @EventListener(ContextRefreshedEvent.class)
    public void initialize(ContextRefreshedEvent event) throws IOException {
        if (!enabled || event.getApplicationContext().getParent()!=null) return;
        try (var stream=resource.getInputStream()) {
            service.importJson(stream.readAllBytes()); // 별도 Spring Bean 호출 → 트랜잭션 적용
        }
    }
}
