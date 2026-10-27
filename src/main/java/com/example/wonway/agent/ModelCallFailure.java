package com.example.wonway.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClientResponseException;
import com.openai.errors.OpenAIServiceException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Emits only allowlisted categories; provider errors can contain API keys or customer text. */
record ModelCallFailure(String code, Integer httpStatus, String message) {
    private static final Pattern HTTP_STATUS = Pattern.compile("^(?:HTTP\\s+)?([1-5][0-9]{2})\\s*[-:]");
    private static final Set<String> QUOTA_CODES = Set.of("insufficient_quota", "credit_balance_exhausted",
            "organization_spend_limit_exceeded", "project_spend_limit_exceeded", "organization_usage_limit_exceeded");

    static ModelCallFailure classify(Throwable exception) {
        Integer status = null;
        boolean timeout = false, connection = false, configuration = false;
        String providerCode = "";
        for (int depth = 0; exception != null && depth < 12; depth++, exception = exception.getCause()) {
            String error = exception.getMessage();
            if (exception instanceof OpenAIServiceException response) {
                status = response.statusCode();
                providerCode = response.code().orElse("");
                error = null;
            } else if (exception instanceof RestClientResponseException response) {
                status = response.getStatusCode().value();
                error = response.getResponseBodyAsString();
            } else if (error != null) {
                var matcher = HTTP_STATUS.matcher(error);
                if (matcher.find()) status = Integer.valueOf(matcher.group(1));
            }
            if (error != null && error.indexOf('{') >= 0) {
                try {
                    // The provider message itself is never returned, logged, or kept as a cause.
                    String candidate = new ObjectMapper().readTree(error.substring(error.indexOf('{'))).path("error").path("code").asText("");
                    if (QUOTA_CODES.contains(candidate) || Set.of("invalid_api_key", "model_not_found",
                            "invalid_function_parameters", "invalid_json_schema").contains(candidate)) providerCode = candidate;
                } catch (IOException ignored) { /* Only a recognized category is useful for diagnostics. */ }
            }
            timeout |= exception instanceof SocketTimeoutException || exception instanceof HttpTimeoutException || exception instanceof TimeoutException;
            connection |= exception instanceof IOException;
            configuration |= exception instanceof IllegalArgumentException || exception instanceof ClassCastException;
        }
        if (Integer.valueOf(401).equals(status) || "invalid_api_key".equals(providerCode))
            return new ModelCallFailure("MODEL_AUTHENTICATION_FAILED", status, "OpenAI 인증에 실패했습니다. 로컬 API 키와 프로젝트 권한을 확인하세요.");
        if (Integer.valueOf(403).equals(status))
            return new ModelCallFailure("MODEL_ACCESS_DENIED", status, "OpenAI 요청 권한을 확인하세요.");
        if (QUOTA_CODES.contains(providerCode))
            return new ModelCallFailure("MODEL_QUOTA_EXCEEDED", status, "OpenAI API 크레딧 또는 프로젝트 사용 한도를 확인하세요.");
        if (Integer.valueOf(429).equals(status))
            return new ModelCallFailure("MODEL_RATE_LIMITED", status, "OpenAI 요청 한도에 도달했습니다. 잠시 후 다시 시도하세요.");
        if (Integer.valueOf(404).equals(status) || "model_not_found".equals(providerCode))
            return new ModelCallFailure("MODEL_NOT_FOUND", status, "요청한 OpenAI 모델과 사용 권한을 확인하세요.");
        if (Set.of("invalid_function_parameters", "invalid_json_schema").contains(providerCode))
            return new ModelCallFailure("MODEL_TOOL_SCHEMA_INVALID", status, "모델에 전달한 도구 입력 스키마를 확인해야 합니다.");
        if (Integer.valueOf(400).equals(status))
            return new ModelCallFailure("MODEL_REQUEST_INVALID", status, "OpenAI 요청 옵션 또는 도구 정의를 확인해야 합니다.");
        if (timeout || Integer.valueOf(408).equals(status))
            return new ModelCallFailure("MODEL_TIMEOUT", status, "상담 모델 응답 대기 시간이 초과됐습니다.");
        if (status != null && status >= 500)
            return new ModelCallFailure("MODEL_PROVIDER_UNAVAILABLE", status, "OpenAI 서비스가 일시적으로 응답하지 않습니다.");
        if (connection)
            return new ModelCallFailure("MODEL_CONNECTION_FAILED", status, "OpenAI 연결을 확인하세요. 네트워크·프록시·인증서 설정이 원인일 수 있습니다.");
        if (configuration)
            return new ModelCallFailure("MODEL_CONFIGURATION_INVALID", status, "상담 모델 요청 설정을 확인해야 합니다.");
        return new ModelCallFailure("MODEL_CALL_FAILED", status, "상담 모델 호출을 완료하지 못했습니다.");
    }
}
