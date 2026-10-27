package com.example.wonway.api;

import com.example.wonway.agent.AgentService;
import com.example.wonway.agent.model.AgentResult;
import com.example.wonway.api.dto.request.ConsultationMessageRequest;
import com.example.wonway.api.dto.response.ConsultationMessageResponse;
import com.example.wonway.api.dto.response.ConsultationSessionResponse;
import com.example.wonway.api.exception.AgentNotReadyException;
import com.example.wonway.api.mapper.ConsultationResponseMapper;
import com.example.wonway.session.ConsultationSessionService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 상담 REST API입니다. 요청 검증 → 활성 세션 확인 → AgentService 호출 → 응답 변환만 담당합니다.
 * 정책 조회·채널 순위·프롬프트는 다루지 않습니다.
 */
@RestController
@RequestMapping("/api/v1/consultations")
public class ConsultationController {

    private static final Logger log = LoggerFactory.getLogger(ConsultationController.class);

    private final ConsultationSessionService sessionService;
    // A의 구현체가 아직 없어도 서버가 뜨도록 ObjectProvider로 받는다. 없으면 메시지 API만 503을 반환한다.
    private final ObjectProvider<AgentService> agentServiceProvider;

    public ConsultationController(ConsultationSessionService sessionService,
                                  ObjectProvider<AgentService> agentServiceProvider) {
        this.sessionService = sessionService;
        this.agentServiceProvider = agentServiceProvider;
    }

    @PostMapping
    public ResponseEntity<ConsultationSessionResponse> createSession() {
        UUID sessionId = sessionService.createSession();
        log.info("상담 시작 sessionId={}", sessionId);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ConsultationSessionResponse(sessionId));
    }

    /**
     * 입력 검증(@Valid)과 세션 확인을 AI 호출보다 먼저 해서, 잘못된 요청이 모델 호출까지 가지 않게 한다.
     */
    @PostMapping("/{sessionId}/messages")
    public ConsultationMessageResponse sendMessage(@PathVariable UUID sessionId,
                                                   @Valid @RequestBody ConsultationMessageRequest request) {
        sessionService.validateActive(sessionId);
        AgentService agentService = agentServiceProvider.getIfAvailable();
        if (agentService == null) {
            throw new AgentNotReadyException();
        }
        // 상담 원문은 로그에 남기지 않고 길이만 기록한다.
        log.info("메시지 수신 sessionId={} length={}", sessionId, request.message().length());
        AgentResult result = agentService.chat(sessionId, request.message());
        return ConsultationResponseMapper.toMessageResponse(sessionId, result);
    }

    /**
     * 세션을 종료 상태로 바꾼다. DB의 정책 데이터는 건드리지 않는다.
     * TODO: A가 AgentService.clearConversation(UUID)를 추가하면 종료 표시 뒤에 호출해 ChatMemory를 삭제한다.
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> closeSession(@PathVariable UUID sessionId) {
        sessionService.closeSession(sessionId);
        log.info("상담 종료 sessionId={}", sessionId);
        return ResponseEntity.noContent().build();
    }
}
