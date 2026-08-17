package com.tvpirate.backend.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.tvpirate.backend.user.GuestCleanupService;

/** Manual triggers for hosts where the scheduler can't fire: scale-to-zero
 * platforms poke this from an external cron (Vercel cron, GitHub Actions).
 * When ADMIN_SECRET is set it must ride along in the X-Admin-Secret header;
 * unset, normal auth alone gates the call. vault:guest-cleanup-deep-dive#cron */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final GuestCleanupService cleanupService;
    private final String adminSecret;

    public AdminController(GuestCleanupService cleanupService,
                           @Value("${app.admin-secret:}") String adminSecret) {
        this.cleanupService = cleanupService;
        this.adminSecret = adminSecret;
    }

    @PostMapping("/cleanup-guests")
    public ResponseEntity<Void> cleanupGuests(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        if (!adminSecret.isBlank() && !adminSecret.equals(secret)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "bad admin secret");
        }
        cleanupService.sweepStaleGuests();
        return ResponseEntity.noContent().build();
    }
}
