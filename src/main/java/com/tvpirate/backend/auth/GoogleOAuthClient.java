package com.tvpirate.backend.auth;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tvpirate.backend.auth.dto.GoogleProfile;

/** Google's side of the sign-in redirect: the consent-screen URL out, then the
 * code that comes back traded for the user's profile. */
@Component
public class GoogleOAuthClient {

    private static final String AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo";

    private final RestClient client;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    @Autowired
    public GoogleOAuthClient(@Value("${google.client-id:}") String clientId,
                             @Value("${google.client-secret:}") String clientSecret,
                             @Value("${google.redirect-uri:http://localhost:8080/api/auth/google/callback}") String redirectUri) {
        this(RestClient.builder().requestFactory(requestFactory()).build(), clientId, clientSecret, redirectUri);
    }

    /** Tests hand in a client bound to a fake Google. */
    GoogleOAuthClient(RestClient client, String clientId, String clientSecret, String redirectUri) {
        this.client = client;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    public boolean isConfigured() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    public String buildAuthorizationUrl(String state) {
        return UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .encode()
                .toUriString();
    }

    /** Both calls go straight to Google over TLS, so the answers need no signature check. */
    public GoogleProfile fetchProfileForCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);

        TokenResponse tokens = client.post()
                .uri(TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);

        return client.get()
                .uri(USERINFO_URL)
                .headers(headers -> headers.setBearerAuth(tokens.accessToken()))
                .retrieve()
                .body(GoogleProfile.class);
    }

    record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }
}
