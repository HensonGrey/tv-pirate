package com.tvpirate.backend.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import com.tvpirate.backend.stream.PublicTargetGuard;
import com.tvpirate.backend.stream.StreamProxyService;

/** The stream lane's caps: PROXY_IN_FLIGHT_PER_OWNER per ticket owner, PROXY_IN_FLIGHT_TOTAL in total. */
class ProxyConcurrencyInterceptorTest {

    private static final int PER_OWNER = RateLimitPolicy.PROXY_IN_FLIGHT_PER_OWNER;
    private static final int TOTAL = RateLimitPolicy.PROXY_IN_FLIGHT_TOTAL;

    private static final String UNKNOWN_TICKET = "0123456789abcdef0123456789abcdef";

    private final StreamProxyService proxyService = new StreamProxyService(new PublicTargetGuard());
    private final ProxyConcurrencyInterceptor interceptor = new ProxyConcurrencyInterceptor(proxyService);

    @Test
    void anOwnerGetsItsCapAtOnceThenABare503() {
        String ticket = ticketOwnedBy(1L);
        assertThat(admit(ticket, PER_OWNER)).allMatch(Attempt::allowed);

        Attempt over = admit(ticket);
        assertThat(over.allowed()).isFalse();
        assertThat(over.response().getStatus()).isEqualTo(503);
        assertThat(over.response().getHeader("Retry-After")).isEqualTo("2");
        assertThat(over.response().getContentAsByteArray()).isEmpty();
    }

    @Test
    void anotherOwnerIsNotBlockedByTheFirst() {
        admit(ticketOwnedBy(1L), PER_OWNER);

        assertThat(admit(ticketOwnedBy(2L)).allowed()).isTrue();
    }

    @Test
    void theTotalCapHoldsAcrossOwners() {
        long owners = TOTAL / PER_OWNER;
        for (long owner = 1; owner <= owners; owner++) {
            assertThat(admit(ticketOwnedBy(owner), PER_OWNER)).allMatch(Attempt::allowed);
        }

        assertThat(admit(ticketOwnedBy(owners + 1)).allowed()).isFalse();
    }

    @Test
    void aSlotIsFreedWhenItsRequestCompletes() {
        String ticket = ticketOwnedBy(1L);
        List<Attempt> running = admit(ticket, PER_OWNER);
        assertThat(admit(ticket).allowed()).isFalse();

        complete(running.getFirst(), null);

        assertThat(admit(ticket).allowed()).isTrue();
    }

    @Test
    void aSlotIsFreedWhenItsRequestFails() {
        String ticket = ticketOwnedBy(1L);
        List<Attempt> running = admit(ticket, PER_OWNER);

        complete(running.getFirst(), new IllegalStateException("CDN died mid-segment"));

        assertThat(admit(ticket).allowed()).isTrue();
    }

    @Test
    void anUnknownTicketCountsTowardTheTotalOnly() {
        assertThat(admit(UNKNOWN_TICKET, TOTAL)).allMatch(Attempt::allowed);

        assertThat(admit(UNKNOWN_TICKET).allowed()).isFalse();
        assertThat(admit(ticketOwnedBy(1L)).allowed()).isFalse();
    }

    // --- helpers ---

    private record Attempt(boolean allowed, MockHttpServletRequest request, MockHttpServletResponse response) {
    }

    private String ticketOwnedBy(long owner) {
        return proxyService.register("https://93.184.216.34/seg.ts", Map.of(), owner);
    }

    private Attempt admit(String ticket) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/stream/proxy/" + ticket);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("token", ticket));
        MockHttpServletResponse response = new MockHttpServletResponse();
        return new Attempt(interceptor.preHandle(request, response, new Object()), request, response);
    }

    private List<Attempt> admit(String ticket, int times) {
        List<Attempt> attempts = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            attempts.add(admit(ticket));
        }
        return attempts;
    }

    private void complete(Attempt attempt, Exception failure) {
        interceptor.afterCompletion(attempt.request(), attempt.response(), new Object(), failure);
    }
}
