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
 * The open lines from the server to each signed-in user's pages, and the notes sent
 * down them. A line is one SSE connection (one {@link SseEmitter}): a user with the
 * site open on a phone and a laptop has two. A note only says what changed
 * ("favourites"), never the data — the page asks for that itself.
 * One lock guards the map; a line is only written to outside it, so one slow
 * client can't hold up the rest.
 */
@Component
public class SyncHub {

    /** Most lines one user may have open; opening another closes their oldest. */
    static final int MAX_LINES_PER_USER = 5;
    /** A line closes after this and the page reconnects. Kept under the access token's
     * life (jwt.access-minutes, 15), so a line can't outlive the login it was opened with. */
    static final Duration LINE_LIFETIME = Duration.ofMinutes(10);
    /** An SSE event with empty data is never delivered, so every note carries something. */
    private static final String NO_ORIGIN = "-";

    /** Open lines per user id, oldest first; a user is removed when their last line closes. */
    private final Map<Long, List<SseEmitter>> lines = new HashMap<>();

    /** Opens a line for the user. Spring sends the returned emitter as the response. */
    public SseEmitter subscribe(long userId) {
        SseEmitter line = new SseEmitter(LINE_LIFETIME.toMillis());
        // However a line ends (finished, timed out, client gone), it leaves the map.
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

    /** Tells every line the user has open that `kind` changed. `origin` is the id of the
     * tab that made the change, so that tab can ignore its own echo; null if unknown. */
    public void publish(long userId, String kind, String origin) {
        String data = origin == null || origin.isBlank() ? NO_ORIGIN : origin;
        for (SseEmitter line : linesOf(userId)) {
            send(userId, line, SseEmitter.event().name(kind).data(data));
        }
    }

    /** Adds the line; returns the user's oldest line if that went over the cap, else null. */
    private synchronized SseEmitter add(long userId, SseEmitter line) {
        List<SseEmitter> open = lines.computeIfAbsent(userId, id -> new ArrayList<>());
        open.add(line);
        return open.size() > MAX_LINES_PER_USER ? open.remove(0) : null;
    }

    private synchronized List<SseEmitter> linesOf(long userId) {
        return List.copyOf(lines.getOrDefault(userId, List.of()));
    }

    private synchronized void remove(long userId, SseEmitter line) {
        List<SseEmitter> open = lines.get(userId);
        if (open != null && open.remove(line) && open.isEmpty()) {
            lines.remove(userId); // idle users don't pile up
        }
    }

    /** A line that can't be written to is dead (client gone, or already completed): drop it. */
    private void send(long userId, SseEmitter line, SseEmitter.SseEventBuilder note) {
        try {
            line.send(note);
        } catch (IOException | IllegalStateException e) {
            remove(userId, line);
        }
    }
}
