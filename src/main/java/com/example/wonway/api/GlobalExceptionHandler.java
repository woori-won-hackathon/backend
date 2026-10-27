package com.example.wonway.api;

import com.example.wonway.api.dto.response.ErrorResponse;
import com.example.wonway.api.exception.AgentNotReadyException;
import com.example.wonway.session.exception.SessionClosedException;
import com.example.wonway.session.exception.SessionNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;

/**
 * 모든 예외를 ErrorResponse 형식으로 변환합니다.
 * 400 입력 오류 / 404 세션 없음 / 409 종료된 세션 / 503 에이전트 미준비 / 500 그 외.
 * 사용자 입력 원문과 내부 예외 메시지는 응답에 넣지 않습니다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String AGENT_FALLBACK_MESSAGE =
            "지금은 추천을 드리기 어렵습니다. 잠시 후 다시 시도하거나 우리은행 공식 채널로 문의해 주세요.";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleInvalidInput(MethodArgumentNotValidException e,
                                                            HttpServletRequest request) {
        // 검증 어노테이션에 적어 둔 문구만 사용하고, 입력값 자체는 응답에 넣지 않는다.
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getDefaultMessage())
                .findFirst()
                .orElse("요청 값이 올바르지 않습니다.");
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 본문을 읽을 수 없습니다.", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSessionId(HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_SESSION_ID", "sessionId 형식이 올바르지 않습니다.", request);
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSessionNotFound(HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "존재하지 않는 상담입니다.", request);
    }

    @ExceptionHandler(SessionClosedException.class)
    public ResponseEntity<ErrorResponse> handleSessionClosed(HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "SESSION_CLOSED", "이미 종료된 상담입니다. 새 상담을 시작해 주세요.", request);
    }

    @ExceptionHandler(AgentNotReadyException.class)
    public ResponseEntity<ErrorResponse> handleAgentNotReady(AgentNotReadyException e, HttpServletRequest request) {
        log.warn(e.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_NOT_READY", AGENT_FALLBACK_MESSAGE, request);
    }

    // TODO: A가 AgentUnavailableException을 추가하면 모델 장애(타임아웃·빈 응답 등)를 503으로 매핑한다.
    //       그 전까지는 아래 500 처리로 떨어진다.

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
        // 없는 경로(404), 허용되지 않은 메서드(405) 같은 Spring MVC 예외는 원래 상태 코드를 유지한다.
        if (e instanceof org.springframework.web.ErrorResponse springError) {
            HttpStatusCode status = springError.getStatusCode();
            return build(status, "HTTP_" + status.value(), "요청을 처리할 수 없습니다.", request);
        }
        log.error("예상하지 못한 오류", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "일시적인 오류가 발생했습니다.", request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatusCode status, String code, String message,
                                                HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(Instant.now(), code, message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
