package com.example.wonway.session;

import com.example.wonway.session.exception.SessionClosedException;
import com.example.wonway.session.exception.SessionNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 메모리 기반 상담 세션 저장소입니다. 서버를 재시작하면 세션이 모두 사라지는 것이 정상입니다.
 * 세션의 상태(ACTIVE/CLOSED)만 관리하며, 대화 내용(ChatMemory)은 다루지 않습니다.
 */
@Service
public class InMemoryConsultationSessionService implements ConsultationSessionService {

    private final ConcurrentMap<UUID, SessionStatus> sessions = new ConcurrentHashMap<>();

    @Override
    public UUID createSession() {
        UUID sessionId = UUID.randomUUID();
        sessions.put(sessionId, SessionStatus.ACTIVE);
        return sessionId;
    }

    @Override
    public void validateActive(UUID sessionId) {
        if (getStatus(sessionId) == SessionStatus.CLOSED) {
            throw new SessionClosedException(sessionId);
        }
    }

    @Override
    public SessionStatus getStatus(UUID sessionId) {
        SessionStatus status = sessions.get(sessionId);
        if (status == null) {
            throw new SessionNotFoundException(sessionId);
        }
        return status;
    }

    @Override
    public void closeSession(UUID sessionId) {
        getStatus(sessionId);
        // ACTIVE일 때만 CLOSED로 바꾸는 원자 연산이라, 같은 세션에 종료 요청이 동시에 와도 하나만 성공한다.
        if (!sessions.replace(sessionId, SessionStatus.ACTIVE, SessionStatus.CLOSED)) {
            throw new SessionClosedException(sessionId);
        }
    }
}
