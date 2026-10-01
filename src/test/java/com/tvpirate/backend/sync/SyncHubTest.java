package com.tvpirate.backend.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.tvpirate.backend.security.AuthedUser;

/** Delivery goes through a real GET /api/events, so the notes land in an actual response
 * body; the cap and the cleanup of dead lines are checked on the emitters themselves. */
class SyncHubTest {

    private static final String READY = "event:ready\ndata:-\n\n";

    private SyncHub hub;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        hub = new SyncHub();
        mockMvc = MockMvcBuilders.standaloneSetup(new SyncController(hub)).build();
    }

    @Test
    void aNewLineIsToldItIsReady() throws Exception {
        assertThat(received(open(1L))).isEqualTo(READY);
    }

    @Test
    void aNoteReachesEveryLineOfThatUserAndNobodyElse() throws Exception {
        MvcResult phone = open(1L);
        MvcResult laptop = open(1L);
        MvcResult stranger = open(2L);

        hub.publish(1L, SyncKindEnum.FAVOURITES, "tabA");

        assertThat(received(phone)).isEqualTo(READY + "event:favourites\ndata:tabA\n\n");
        assertThat(received(laptop)).isEqualTo(READY + "event:favourites\ndata:tabA\n\n");
        assertThat(received(stranger)).isEqualTo(READY);
    }

    @Test
    void theNoteIsNamedAfterItsKind() throws Exception {
        MvcResult line = open(1L);

        hub.publish(1L, SyncKindEnum.PROGRESS, "tabA");

        assertThat(received(line)).endsWith("event:progress\ndata:tabA\n\n");
    }

    @Test
    void aMissingOrBlankOriginBecomesAPlaceholder() throws Exception {
        MvcResult line = open(1L);

        hub.publish(1L, SyncKindEnum.FAVOURITES, null);
        hub.publish(1L, SyncKindEnum.FAVOURITES, " ");

        assertThat(received(line))
                .isEqualTo(READY + "event:favourites\ndata:-\n\n" + "event:favourites\ndata:-\n\n");
    }

    @Test
    void publishingToAUserWithNoLinesDoesNothing() {
        assertThatCode(() -> hub.publish(99L, SyncKindEnum.FAVOURITES, "tabA"))
                .doesNotThrowAnyException();
    }

    @Test
    void theHeartbeatPingsEveryLineWithoutANote() throws Exception {
        MvcResult line = open(1L);

        hub.heartbeat();

        assertThat(received(line)).isEqualTo(READY + ":hb\n\n");
    }

    @Test
    void theSixthLineClosesTheUsersOldest() {
        SseEmitter oldest = hub.subscribe(1L);
        for (int i = 1; i < SyncHub.MAX_LINES_PER_USER; i++) {
            hub.subscribe(1L);
        }
        assertThatCode(() -> oldest.send("still open")).doesNotThrowAnyException();

        hub.subscribe(1L);

        assertThatThrownBy(() -> oldest.send("closed")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theCapIsPerUser() {
        SseEmitter firstOfUserOne = hub.subscribe(1L);
        for (int i = 1; i < SyncHub.MAX_LINES_PER_USER; i++) {
            hub.subscribe(1L);
        }

        for (int i = 0; i < SyncHub.MAX_LINES_PER_USER; i++) {
            hub.subscribe(2L);
        }

        assertThatCode(() -> firstOfUserOne.send("still open")).doesNotThrowAnyException();
    }

    @Test
    void aLineThatCantBeWrittenToIsDroppedWhenAPublishFindsIt() {
        SseEmitter first = fillToTheCapWithTheNewestLineGone();

        hub.publish(1L, SyncKindEnum.FAVOURITES, null);
        hub.subscribe(1L); // over the cap again only if the dead line were still counted

        assertThatCode(() -> first.send("still open")).doesNotThrowAnyException();
    }

    @Test
    void aLineThatCantBeWrittenToIsDroppedWhenTheHeartbeatFindsIt() {
        SseEmitter first = fillToTheCapWithTheNewestLineGone();

        hub.heartbeat();
        hub.subscribe(1L);

        assertThatCode(() -> first.send("still open")).doesNotThrowAnyException();
    }

    /** Opens the most lines the user may have, then closes the newest from the client's side.
     * Returns the oldest, which stays alive unless something miscounts. */
    private SseEmitter fillToTheCapWithTheNewestLineGone() {
        SseEmitter oldest = hub.subscribe(1L);
        for (int i = 1; i < SyncHub.MAX_LINES_PER_USER - 1; i++) {
            hub.subscribe(1L);
        }
        hub.subscribe(1L).complete();
        return oldest;
    }

    private MvcResult open(long userId) throws Exception {
        var signedIn = new UsernamePasswordAuthenticationToken(
                new AuthedUser(userId, "user" + userId, "GUEST", null), null, List.of());
        return mockMvc.perform(get("/api/events").principal(signedIn)).andReturn();
    }

    private static String received(MvcResult line) throws Exception {
        return line.getResponse().getContentAsString();
    }
}
