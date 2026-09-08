package com.tvpirate.backend.auth;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Daily prune of expired refresh tokens. The guest sweep only ever removes
 * tokens belonging to guests it deletes, so a provider-backed session
 * abandoned without logging out would otherwise leave its row forever.
 * vault:auth-deep-dive#tokens */
@Service
public class RefreshTokenCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupService.class);

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenCleanupService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /** Expired means past its own expiry, so this can never end a live
     * session — rotation has already burned every token still in use.
     * Runs after the guest sweep so the two don't contend for the same rows. */
    @Scheduled(cron = "${app.token-cleanup-cron:0 32 3 * * *}")
    @Transactional
    public void pruneExpiredTokens() {
        long removed = refreshTokenRepository.deleteAllByExpiresAtBefore(Instant.now());
        if (removed > 0) {
            log.info("Refresh token cleanup pruned {} expired tokens", removed);
        }
    }
}
