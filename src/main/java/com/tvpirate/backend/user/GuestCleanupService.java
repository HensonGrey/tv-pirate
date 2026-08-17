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

import com.tvpirate.backend.auth.RefreshTokenRepository;
import com.tvpirate.backend.favourite.FavouriteRepository;
import com.tvpirate.backend.progress.WatchProgressRepository;

/** Daily sweep of abandoned guest accounts: free Postgres tiers are small,
 * so guests with no activity for the retention window are deleted with
 * everything they own. Also callable manually (AdminController) for hosts
 * where the scheduler can't fire. vault:guest-cleanup-deep-dive#cron */
@Service
public class GuestCleanupService {

    private static final Logger log = LoggerFactory.getLogger(GuestCleanupService.class);

    private final UserRepository userRepository;
    private final FavouriteRepository favouriteRepository;
    private final WatchProgressRepository watchProgressRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final long retentionDays;

    public GuestCleanupService(UserRepository userRepository,
                               FavouriteRepository favouriteRepository,
                               WatchProgressRepository watchProgressRepository,
                               RefreshTokenRepository refreshTokenRepository,
                               @Value("${app.guest-retention-days:7}") long retentionDays) {
        this.userRepository = userRepository;
        this.favouriteRepository = favouriteRepository;
        this.watchProgressRepository = watchProgressRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.retentionDays = retentionDays;
    }

    /** One pass: stale guests -> their rows -> the users. The deletes fire
     * the activity trigger one last time, but those users leave anyway. */
    @Scheduled(cron = "${app.guest-cleanup-cron:0 17 3 * * *}")
    @Transactional
    public void sweepStaleGuests() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        List<UserEntity> stale =
                userRepository.findByProviderAndLastActivityAtBefore(AuthProvider.GUEST, cutoff);
        if (stale.isEmpty()) {
            return;
        }
        List<Long> ids = stale.stream().map(UserEntity::getId).toList();
        favouriteRepository.deleteAllByUserIdIn(ids);
        watchProgressRepository.deleteAllByUserIdIn(ids);
        refreshTokenRepository.deleteAllByUserIn(stale);
        userRepository.deleteAll(stale);
        log.info("Guest cleanup swept {} stale accounts", stale.size());
    }
}
