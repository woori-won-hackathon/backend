package com.example.wonway.session;

import com.example.wonway.session.exception.SessionClosedException;
import com.example.wonway.session.exception.SessionNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryConsultationSessionServiceTest {

    private final InMemoryConsultationSessionService service = new InMemoryConsultationSessionService();

    @Test
    void 새_세션은_고유한_id를_받고_활성_상태다() {
        UUID a = service.createSession();
        UUID b = service.createSession();

        assertThat(a).isNotEqualTo(b);
        assertThat(service.getStatus(a)).isEqualTo(SessionStatus.ACTIVE);
        service.validateActive(a);
    }

    @Test
    void 없는_세션은_NotFound() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> service.validateActive(unknown)).isInstanceOf(SessionNotFoundException.class);
        assertThatThrownBy(() -> service.getStatus(unknown)).isInstanceOf(SessionNotFoundException.class);
        assertThatThrownBy(() -> service.closeSession(unknown)).isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void 종료한_세션은_다시_쓸_수_없다() {
        UUID id = service.createSession();
        service.closeSession(id);

        assertThat(service.getStatus(id)).isEqualTo(SessionStatus.CLOSED);
        assertThatThrownBy(() -> service.validateActive(id)).isInstanceOf(SessionClosedException.class);
        assertThatThrownBy(() -> service.closeSession(id)).isInstanceOf(SessionClosedException.class);
    }

    @Test
    void 한_세션을_종료해도_다른_세션은_영향이_없다() {
        UUID a = service.createSession();
        UUID b = service.createSession();
        service.closeSession(a);

        service.validateActive(b);
    }

    @Test
    void 동시에_종료를_요청해도_한_번만_성공한다() throws Exception {
        UUID id = service.createSession();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.await();
                try {
                    service.closeSession(id);
                    success.incrementAndGet();
                } catch (SessionClosedException e) {
                    conflict.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();

        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
    }
}
