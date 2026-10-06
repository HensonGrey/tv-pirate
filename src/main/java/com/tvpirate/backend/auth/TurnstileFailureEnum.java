package com.tvpirate.backend.auth;

import java.util.List;
import java.util.Set;

/** Why a guest's bot check failed: the 403 text that names the cause, and the
 * Cloudflare siteverify error codes that mean it. */
public enum TurnstileFailureEnum {

    NO_TOKEN("Bot check misconfigured: no Turnstile token was sent. The frontend's VITE_TURNSTILE_SITE_KEY is probably unset."),
    BAD_SECRET("Bot check misconfigured: Cloudflare rejected the server's Turnstile secret key.",
            "missing-input-secret", "invalid-input-secret"),
    BAD_TOKEN("Bot check failed: Cloudflare rejected the token. The site key and secret key may belong to different widgets.",
            "missing-input-response", "invalid-input-response"),
    EXPIRED("Bot check expired or was already used. Please try again.",
            "timeout-or-duplicate"),
    UNREACHABLE("Couldn't reach Cloudflare for the bot check. Please try again."),
    CLOUDFLARE_ERROR("Cloudflare couldn't complete the bot check. Please try again.");

    private final String message;
    private final Set<String> errorCodes;

    TurnstileFailureEnum(String message, String... errorCodes) {
        this.message = message;
        this.errorCodes = Set.of(errorCodes);
    }

    public String message() {
        return message;
    }

    /** Declaration order is priority order: a bad secret outranks a bad token when Cloudflare reports both. */
    static TurnstileFailureEnum fromErrorCodes(List<String> codes) {
        for (TurnstileFailureEnum failure : values()) {
            if (codes.stream().anyMatch(failure.errorCodes::contains)) {
                return failure;
            }
        }
        return CLOUDFLARE_ERROR;
    }
}
