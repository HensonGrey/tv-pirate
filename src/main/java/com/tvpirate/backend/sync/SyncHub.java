package com.tvpirate.backend.sync;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Each signed-in user's open SSE connections ("lines") and the notes sent down them.
 * A note says only what changed (a {@link SyncKindEnum}), never the data.
 */
@Component
public class SyncHub {

    static final int MAX_LINES_PER_USER = 5;
    // Under the access token's 15 minutes, so a line can't outlive its login.
    static final Duration LINE_LIFETIME = Duration.ofMinutes(10);
    // An SSE event with empty data is never delivered.
    private static final String NO_ORIGIN = "-";

    // Oldest first.
    private final Map<Long, List<SseEmitter>> lines = new HashMap<>();

    public SseEmitter subscribe(long userId) {
        SseEmitter line = new SseEmitter(LINE_LIFETIME.toMillis());
        // Every way a line can end removes it.
        line.onCompletion(() -> remove(userId, line));
        line.onTimeout(() -> remove(userId, line));
        line.onError(error -> remove(userId, line));

        SseEmitter oldest = add(userId, line);
        if (oldest != null) {
            oldest.complete();
        }
        send(userId, line, SseEmitter.event().name("ready").data(NO_ORIGIN));
        return line;
    }

    /** `origin` is the tab that made the change, so it can skip its own echo. */
    public void publish(long userId, SyncKindEnum kind, String origin) {
        String data = origin == null || origin.isBlank() ? NO_ORIGIN : origin;
        for (SseEmitter line : linesOf(userId)) {
            send(userId, line, SseEmitter.event().name(kind.eventName()).data(data));
        }
    }

    /** Returns the user's oldest line if this one went over the cap. */
    private synchronized SseEmitter add(long userId, SseEmitter line) {
        List<SseEmitter> open = lines.computeIfAbsent(userId, id -> new ArrayList<>());
        open.add(line);
        return open.size() > MAX_LINES_PER_USER ? open.remove(0) : null;
    }

    // A copy, so publish can write to the lines outside the lock.
    private synchronized List<SseEmitter> linesOf(long userId) {
        return List.copyOf(lines.getOrDefault(userId, List.of()));
    }

    private synchronized void remove(long userId, SseEmitter line) {
        List<SseEmitter> open = lines.get(userId);
        if (open != null && open.remove(line) && open.isEmpty()) {
            lines.remove(userId);
        }
    }

    /** A line that can't be written to is dead: drop it. */
    private void send(long userId, SseEmitter line, SseEmitter.SseEventBuilder note) {
        try {
            line.send(note);
        } catch (IOException | IllegalStateException e) {
            remove(userId, line);
        }
    }
}
