package com.tvpirate.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.server.ResponseStatusException;

import com.jayway.jsonpath.JsonPath;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The 5xx-scrubbing contract. Every assertion is either "the client is told
 * nothing" or "the log is told everything" — the two together are the whole
 * point of the handler.
 */
class GlobalExceptionHandlerTest {

    /** Stand-ins for the real leaks: a DB URL with a password, the SSRF guard's
     *  blocked host, and the subtitle service's .env hint. */
    private static final String DB_SECRET = "jdbc:postgresql://localhost:5432/tv-pirate?password=hunter2";
    private static final String BLOCKED_HOST = "169.254.169.254";
    private static final String ENV_HINT = "add OPENSUBTITLES_API_KEY to backend/.env";

    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> logs;
    private Logger handlerLogger;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        logs = new ListAppender<>();
        logs.start();
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        handlerLogger.setLevel(Level.DEBUG);
        handlerLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logs);
    }

    // --- what the client is told ---

    @Test
    void unhandledExceptionIsAnOpaque500() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/unhandled")).andReturn();
        String body = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentType()).contains("application/problem+json");
        assertThat(JsonPath.<Integer>read(body, "$.status")).isEqualTo(500);
        assertThat(JsonPath.<String>read(body, "$.detail"))
                .isEqualTo("Something went wrong on our side. Please try again.");
        assertThat(JsonPath.<String>read(body, "$.title")).isEqualTo("Internal Server Error");
    }

    @Test
    void the500BodyLeaksNoInternals() throws Exception {
        String body = mockMvc.perform(get("/test/unhandled")).andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain(DB_SECRET)
                .doesNotContain("hunter2")
                .doesNotContain("jdbc")
                .doesNotContain("IllegalStateException")
                .doesNotContain("com.tvpirate")
                .doesNotContain("\tat ");
    }

    @Test
    void aNestedCauseIsNotUnwrappedIntoTheBody() throws Exception {
        String body = mockMvc.perform(get("/test/nested-cause")).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("hunter2").doesNotContain("SQLException");
    }

    @Test
    void anAuthored5xxReasonIsStillScrubbed() throws Exception {
        // PublicTargetGuard's real message — echoing it back confirms an SSRF probe.
        MvcResult result = mockMvc.perform(get("/test/blocked-target")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(BLOCKED_HOST)
                .doesNotContain("blocked stream target");
    }

    @Test
    void a503ConfigHintStaysOnTheServer() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/no-api-key")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(ENV_HINT)
                .doesNotContain("OPENSUBTITLES_API_KEY")
                .doesNotContain(".env");
    }

    @Test
    void authored4xxMessagesStillReachTheClient() throws Exception {
        // The other half of the contract: scrubbing 5xx must not silence the
        // deliberate validation messages the controllers write.
        MvcResult result = mockMvc.perform(get("/test/bad-request")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("type must be movie or tv");
    }

    @Test
    void springsOwnValidationErrorsStayAs4xx() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/needs-param")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentType()).contains("application/problem+json");
    }

    // --- what the log is told ---

    @Test
    void theErrorIdTiesTheBodyToTheStackTrace() throws Exception {
        String body = mockMvc.perform(get("/test/unhandled")).andReturn().getResponse().getContentAsString();
        String errorId = JsonPath.read(body, "$.errorId");

        assertThat(errorId).isNotBlank();

        ILoggingEvent logged = onlyEventAt(Level.ERROR);
        assertThat(logged.getFormattedMessage()).contains(errorId).contains("/test/unhandled").contains("GET");
        // The stack the client never sees has to be here, or scrubbing is just losing information.
        assertThat(logged.getThrowableProxy()).isNotNull();
        assertThat(logged.getThrowableProxy().getMessage()).contains("hunter2");
    }

    @Test
    void eachFailureGetsItsOwnErrorId() throws Exception {
        String first = errorIdOf(mockMvc.perform(get("/test/unhandled")).andReturn());
        String second = errorIdOf(mockMvc.perform(get("/test/unhandled")).andReturn());

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void aClientHangingUpIsNotLoggedAsAServerFailure() throws Exception {
        // The playback proxy sees this on every seek — at ERROR it would bury the log.
        mockMvc.perform(get("/test/client-abort")).andReturn();

        assertThat(eventsAt(Level.ERROR)).isEmpty();
        assertThat(eventsAt(Level.DEBUG))
                .anyMatch(event -> event.getFormattedMessage().contains("client disconnected"));
    }

    // --- boundaries the handler must not cross ---

    @Test
    void accessDeniedIsLeftToSpringSecurity() {
        // Swallowing this would turn every future @PreAuthorize denial into a 500.
        Throwable thrown = catchThrowable(() -> mockMvc.perform(get("/test/denied")));

        assertThat(causeChainOf(thrown)).hasAtLeastOneElementOfType(AccessDeniedException.class);
    }

    @Test
    void anAlreadyCommittedResponseIsLeftAlone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/stream/proxy/token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.flushBuffer(); // the proxy has already streamed part of a segment

        var rewritten = new GlobalExceptionHandler().handleExceptionInternal(
                new IllegalStateException("upstream died mid-segment"), null, new HttpHeaders(),
                HttpStatus.INTERNAL_SERVER_ERROR, new ServletWebRequest(request, response));

        assertThat(rewritten).isNull();
    }

    // --- helpers ---

    private static String errorIdOf(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.errorId");
    }

    private List<ILoggingEvent> eventsAt(Level level) {
        return logs.list.stream().filter(event -> event.getLevel() == level).toList();
    }

    private ILoggingEvent onlyEventAt(Level level) {
        List<ILoggingEvent> events = eventsAt(level);
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    private static List<Throwable> causeChainOf(Throwable thrown) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = thrown; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }

    /** Every way the API can fail, in one place. */
    @RestController
    static class ThrowingController {

        @GetMapping("/test/unhandled")
        String unhandled() {
            throw new IllegalStateException("could not reach " + DB_SECRET);
        }

        @GetMapping("/test/nested-cause")
        String nestedCause() {
            throw new RuntimeException("wrapper", new SQLException("auth failed for " + DB_SECRET));
        }

        @GetMapping("/test/blocked-target")
        String blockedTarget() {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "blocked stream target host: " + BLOCKED_HOST);
        }

        @GetMapping("/test/no-api-key")
        String noApiKey() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OpenSubtitles API key not configured — " + ENV_HINT);
        }

        @GetMapping("/test/bad-request")
        String badRequest() {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be movie or tv");
        }

        @GetMapping("/test/denied")
        String denied() {
            throw new AccessDeniedException("not yours");
        }

        @GetMapping("/test/client-abort")
        String clientAbort() {
            throw new IllegalStateException(new IOException("Broken pipe"));
        }

        @GetMapping("/test/needs-param")
        String needsParam(@RequestParam String type) {
            return type;
        }
    }
}
