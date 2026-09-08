package com.tvpirate.backend.user;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Daily sweep of abandoned guest accounts: free Postgres tiers are small,
 * so guests with no activity for the retention window are deleted with
 * everything they own. vault:guest-cleanup-deep-dive#cron */
@Service
public class GuestCleanupService {

    private static final Logger log = LoggerFactory.getLogger(GuestCleanupService.class);

    private final UserRepository userRepository;
    private final long retentionDays;

    public GuestCleanupService(UserRepository userRepository,
                               @Value("${app.guest-retention-days:7}") long retentionDays) {
        this.userRepository = userRepository;
        this.retentionDays = retentionDays;
    }

    /** One pass: stale guests, deleted. ON DELETE CASCADE takes their
     * favourites/watch-progress/refresh-token rows with them. */
    @Scheduled(cron = "${app.guest-cleanup-cron:0 17 3 * * *}")
    @Transactional
    public void sweepStaleGuests() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        List<UserEntity> stale =
                userRepository.findByProviderAndLastActivityAtBefore(AuthProvider.GUEST, cutoff);
        if (stale.isEmpty()) {
            return;
        }
        userRepository.deleteAll(stale);
        log.info("Guest cleanup swept {} stale accounts", stale.size());
    }
}
