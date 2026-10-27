package com.example.wonway.agent;

import com.example.wonway.session.exception.SessionClosedException;
import com.example.wonway.session.exception.SessionNotFoundException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** Successful turns only, in server memory. Never log conversation content. */
@Component
public class ConversationMemory {
    private static final int MAX_TURNS = 8;
    private final ConcurrentHashMap<UUID, Entry> sessions = new ConcurrentHashMap<>();

    public record Turn(String user, String assistant, ConsultationUnderstanding understanding) {
        public Turn(String user, String assistant) { this(user, assistant, null); }
    }

    private static final class Entry {
        final ReentrantLock processing = new ReentrantLock();
        // null is a terminal closed state, including while a model call is in progress.
        final AtomicReference<List<Turn>> turns = new AtomicReference<>(List.of());
    }

    public void open(UUID sessionId) {
        if (sessions.putIfAbsent(sessionId, new Entry()) != null) {
            throw new IllegalStateException("Conversation already exists");
        }
    }

    public void close(UUID sessionId) {
        Entry entry = sessions.remove(sessionId);
        if (entry != null) entry.turns.set(null);
    }

    /** Serialize one session without blocking other customers or waiting to close a session. */
    public <T> T respond(UUID sessionId, String message,
                         Function<List<Turn>, T> generate, Function<T, String> answer) {
        return respond(sessionId, message, generate, answer, result -> null);
    }

    public <T> T respond(UUID sessionId, String message,
                         Function<List<Turn>, T> generate, Function<T, String> answer,
                         Function<T, ConsultationUnderstanding> understanding) {
        Entry entry = sessions.get(sessionId);
        if (entry == null) throw new SessionNotFoundException(sessionId);
        entry.processing.lock();
        try {
            List<Turn> before = entry.turns.get();
            if (before == null) throw new SessionClosedException(sessionId);
            T result = generate.apply(before);
            List<Turn> updated = new ArrayList<>(before);
            updated.add(new Turn(message, answer.apply(result), understanding.apply(result)));
            if (updated.size() > MAX_TURNS) updated.remove(0);
            // Closing a session during generation must not restore its erased history.
            if (!entry.turns.compareAndSet(before, List.copyOf(updated))) {
                throw new SessionClosedException(sessionId);
            }
            return result;
        } finally {
            entry.processing.unlock();
        }
    }
}
