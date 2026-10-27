package com.example.wonway.agent;

import org.junit.jupiter.api.Test;
import java.net.SocketTimeoutException;
import static org.assertj.core.api.Assertions.*;

class ModelCallFailureTest {
    @Test void classifiesAuthenticationWithoutExposingProviderMessageOrKey() {
        var failure = ModelCallFailure.classify(new RuntimeException("HTTP 401 - {\"error\":{\"code\":\"invalid_api_key\",\"message\":\"key: sk-test-secret\"}}"));
        assertThat(failure.code()).isEqualTo("MODEL_AUTHENTICATION_FAILED");
        assertThat(failure.httpStatus()).isEqualTo(401);
        assertThat(failure.toString()).doesNotContain("sk-test-secret");
    }
    @Test void distinguishesBillingFromTemporaryRateLimitAndRecognizesToolSchemaErrors() {
        assertThat(ModelCallFailure.classify(new RuntimeException("HTTP 429 - {\"error\":{\"code\":\"insufficient_quota\"}}" )).code())
                .isEqualTo("MODEL_QUOTA_EXCEEDED");
        assertThat(ModelCallFailure.classify(new RuntimeException("HTTP 429 - {}" )).code()).isEqualTo("MODEL_RATE_LIMITED");
        assertThat(ModelCallFailure.classify(new RuntimeException("400 - {\"error\":{\"code\":\"invalid_function_parameters\"}}" )).code())
                .isEqualTo("MODEL_TOOL_SCHEMA_INVALID");
    }
    @Test void followsNestedTransportErrorsAndDiscardsUnknownErrorDetails() {
        assertThat(ModelCallFailure.classify(new RuntimeException(new SocketTimeoutException("private request details"))).code()).isEqualTo("MODEL_TIMEOUT");
        assertThat(ModelCallFailure.classify(new IllegalStateException("private request details")).toString()).doesNotContain("private request details");
    }

    @Test void classifiesOfficialSdkStatusWithoutCopyingResponseText() {
        var sdk = org.mockito.Mockito.mock(com.openai.errors.OpenAIServiceException.class);
        org.mockito.Mockito.when(sdk.statusCode()).thenReturn(401);
        org.mockito.Mockito.when(sdk.code()).thenReturn(java.util.Optional.of("invalid_api_key"));
        org.mockito.Mockito.when(sdk.getMessage()).thenReturn("sk-test-secret");
        var failure = ModelCallFailure.classify(sdk);
        assertThat(failure.code()).isEqualTo("MODEL_AUTHENTICATION_FAILED");
        assertThat(failure.httpStatus()).isEqualTo(401);
        assertThat(failure.toString()).doesNotContain("sk-test-secret");
    }
}
