package com.tvpirate.backend.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.tvpirate.backend.stream.TicketSealer.Unsealed;

class TicketSealerTest {

    private static final String ROOT = "0123456789abcdef0123456789abcdef";
    private static final String URL = "https://cdn.example/hls/seg-1.ts?token=a%2Fb&exp=1790000000";

    private final TicketSealer sealer = new TicketSealer();

    @Test
    void aSealedTicketOpensToItsRootAndUrl() {
        String ticket = sealer.seal(ROOT, URL);

        assertThat(ticket).startsWith("s.").doesNotContain("cdn.example");
        assertThat(sealer.unseal(ticket)).isEqualTo(new Unsealed(ROOT, URL));
    }

    @Test
    void theTicketIsUrlSafe() {
        assertThat(sealer.seal(ROOT, URL).substring(2)).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void sealingTheSameTargetTwiceGivesDifferentTickets() {
        assertThat(sealer.seal(ROOT, URL)).isNotEqualTo(sealer.seal(ROOT, URL));
    }

    @Test
    void unicodeInTheUrlSurvives() {
        String url = "https://cdn.example/фильм/сегмент-1.ts";

        assertThat(sealer.unseal(sealer.seal(ROOT, url)).childUrl()).isEqualTo(url);
    }

    @Test
    void aTamperedTicketDoesNotOpen() {
        String ticket = sealer.seal(ROOT, URL);
        byte[] raw = Base64.getUrlDecoder().decode(ticket.substring(2));
        raw[raw.length / 2] ^= 1;

        assertThat(sealer.unseal("s." + Base64.getUrlEncoder().withoutPadding().encodeToString(raw))).isNull();
    }

    @Test
    void aTicketFromAnotherKeyDoesNotOpen() {
        assertThat(sealer.unseal(new TicketSealer().seal(ROOT, URL))).isNull();
    }

    @Test
    void junkDoesNotOpen() {
        String ticket = sealer.seal(ROOT, URL);

        assertThat(sealer.unseal(ticket.substring(0, 20))).isNull();
        assertThat(sealer.unseal("s.")).isNull();
        assertThat(sealer.unseal("s.not*base64!")).isNull();
        assertThat(sealer.unseal(ROOT)).isNull();
        assertThat(sealer.unseal(null)).isNull();
    }

    @Test
    void onlyThePrefixMarksATicketAsSealed() {
        assertThat(TicketSealer.isSealed(sealer.seal(ROOT, URL))).isTrue();
        assertThat(TicketSealer.isSealed(ROOT)).isFalse();
    }

    @Test
    void aRootMustBeAFullToken() {
        assertThatThrownBy(() -> sealer.seal("short", URL)).isInstanceOf(IllegalArgumentException.class);
    }
}
