package com.tvpirate.backend.auth;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Cloudflare Turnstile's server-side check of the token its widget hands the browser.
 * It stops scripted guest creation whatever IP the script rotates through. vault:rate-limiting-deep-dive#vpn */
@Component
public class TurnstileClient {

    private static final Logger log = LoggerFactory.getLogger(TurnstileClient.class);

    private static final String SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final RestClient client;
    private final String secretKey;

    @Autowired
    public TurnstileClient(@Value("${turnstile.secret-key:}") String secretKey) {
        this(RestClient.builder().requestFactory(requestFactory()).build(), secretKey);
        if (!isConfigured()) {
            log.warn("TURNSTILE_SECRET_KEY is not set, so guest creation skips the bot check");
        }
    }

    /** Tests hand in a client bound to a fake Cloudflare. */
    TurnstileClient(RestClient client, String secretKey) {
        this.client = client;
        this.secretKey = secretKey;
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    public boolean isConfigured() {
        return !secretKey.isBlank();
    }

    /** Empty when the token passes. Fails closed: a token Cloudflare can't vouch for, even because it's unreachable, is a no. */
    public Optional<TurnstileFailureEnum> findFailure(String token, String remoteIp) {
        if (token == null || token.isBlank()) {
            return Optional.of(TurnstileFailureEnum.NO_TOKEN);
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secretKey);
        form.add("response", token);
        form.add("remoteip", remoteIp);
        SiteverifyResponse result;
        try {
            result = client.post()
                    .uri(SITEVERIFY_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(SiteverifyResponse.class);
        } catch (RestClientException e) {
            log.warn("Turnstile siteverify failed: {}", e.getMessage());
            return Optional.of(TurnstileFailureEnum.UNREACHABLE);
        }
        if (result == null) {
            return Optional.of(TurnstileFailureEnum.CLOUDFLARE_ERROR);
        }
        if (result.success()) {
            return Optional.empty();
        }
        List<String> codes = result.errorCodes() == null ? List.of() : result.errorCodes();
        log.warn("Turnstile refused a token: {}", codes);
        return Optional.of(TurnstileFailureEnum.fromErrorCodes(codes));
    }

    record SiteverifyResponse(boolean success, @JsonProperty("error-codes") List<String> errorCodes) {
    }
}
