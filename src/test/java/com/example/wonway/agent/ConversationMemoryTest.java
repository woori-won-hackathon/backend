package com.example.wonway.agent;

import com.example.wonway.session.InMemoryConsultationSessionService;
import com.example.wonway.session.exception.SessionClosedException;
import com.example.wonway.session.exception.SessionNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationMemoryTest {
    private final ConversationMemory memory = new ConversationMemory();
    private final InMemoryConsultationSessionService sessions = new InMemoryConsultationSessionService(memory);

    @Test
    void keepsOnlySuccessfulTurnsAndIsolatesNewSessions() {
        UUID first = sessions.createSession(), second = sessions.createSession();
        memory.respond(first, "자동이체 문의", history -> "변경일은 언제인가요?", answer -> answer);
        assertThatThrownBy(() -> memory.respond(first, "실패한 질문", history -> {
            throw new IllegalStateException("model unavailable");
        }, answer -> "unused")).isInstanceOf(IllegalStateException.class);
        memory.respond(first, "26일이요", history -> {
            assertThat(history).containsExactly(new ConversationMemory.Turn("자동이체 문의", "변경일은 언제인가요?"));
            return "26일 변경 절차 안내";
        }, answer -> answer);
        memory.respond(second, "새 문의", history -> {
            assertThat(history).isEmpty();
            return "새 안내";
        }, answer -> answer);
    }

    @Test
    void retainsAtMostEightCompleteTurns() {
        UUID id = sessions.createSession();
        for (int i = 0; i < 12; i++) {
            memory.respond(id, "질문" + i, history -> "안내", answer -> answer);
        }
        memory.respond(id, "후속 문의", history -> {
            assertThat(history).hasSize(8);
            assertThat(history.get(0).user()).isEqualTo("질문4");
            assertThat(history.get(7).user()).isEqualTo("질문11");
            return "안내";
        }, answer -> answer);
    }

    @Test
    void closingErasesHistoryAndPreventsLateResponseFromRestoringIt() throws Exception {
        UUID id = sessions.createSession();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var pending = pool.submit(() -> memory.respond(id, "문의", history -> {
                started.countDown();
                await(release);
                return "늦은 응답";
            }, answer -> answer));
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                sessions.closeSession(id);
                assertThatThrownBy(() -> memory.respond(id, "재사용", history -> "응답", answer -> answer))
                        .isInstanceOf(SessionNotFoundException.class);
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(SessionClosedException.class);
        }
    }

    @Test
    void concurrentTurnsInOneSessionAreSerializedWhileAnotherSessionCanRespond() throws Exception {
        UUID id = sessions.createSession(), other = sessions.createSession();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> memory.respond(id, "첫 질문", history -> {
                started.countDown();
                await(release);
                return "첫 답변";
            }, answer -> answer));
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> memory.respond(id, "둘째 질문", history -> {
                    assertThat(history).isEqualTo(List.of(new ConversationMemory.Turn("첫 질문", "첫 답변")));
                    return "둘째 답변";
                }, answer -> answer));
                memory.respond(other, "다른 고객", history -> {
                    assertThat(history).isEmpty();
                    return "별도 답변";
                }, answer -> answer);
                release.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("첫 답변");
                assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("둘째 답변");
            } finally {
                release.countDown();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
