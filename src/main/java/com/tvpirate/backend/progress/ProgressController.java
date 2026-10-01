package com.tvpirate.backend.progress;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.tvpirate.backend.progress.dto.ProgressRowDto;
import com.tvpirate.backend.progress.dto.SaveProgressRequest;
import com.tvpirate.backend.security.AuthedUser;
import com.tvpirate.backend.sync.SyncHub;
import com.tvpirate.backend.sync.SyncKindEnum;

/** Per-user watch history: the player heartbeats positions here and the home
 * screen reads them back for its progress bars. vault:watch-progress-deep-dive#schema */
@RestController
@RequestMapping("/api/progress")
public class ProgressController {

    private final ProgressService progressService;

    private final SyncHub syncHub;

    public ProgressController(ProgressService progressService, SyncHub syncHub) {
        this.progressService = progressService;
        this.syncHub = syncHub;
    }

    /** All rows for the caller, newest first — the frontend picks its winner. */
    @GetMapping
    public List<ProgressRowDto> list(Authentication authentication) {
        AuthedUser principal = (AuthedUser) authentication.getPrincipal();
        return progressService.list(principal.id());
    }

    @PutMapping
    public ResponseEntity<Void> save(@RequestBody SaveProgressRequest request,
                                     @RequestHeader(name = SyncHub.CLIENT_ID_HEADER, required = false) String clientId,
                                     Authentication authentication) {
        validate(request);
        AuthedUser principal = (AuthedUser) authentication.getPrincipal();
        progressService.upsert(principal.id(), request);
        syncHub.publish(principal.id(), SyncKindEnum.PROGRESS, clientId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{type}/{tmdbId}")
    public ResponseEntity<Void> delete(@PathVariable String type,
                                       @PathVariable long tmdbId,
                                       @RequestParam(required = false) Integer season,
                                       @RequestParam(required = false) Integer episode,
                                       @RequestHeader(name = SyncHub.CLIENT_ID_HEADER, required = false) String clientId,
                                       Authentication authentication) {
        if (!type.equals("movie") && !type.equals("tv")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be movie or tv");
        }
        // Coordinates come as a pair or not at all — one alone would target nothing.
        if ((season == null) != (episode == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "season and episode must be set together");
        }
        AuthedUser principal = (AuthedUser) authentication.getPrincipal();
        progressService.delete(principal.id(), type, tmdbId, season, episode);
        syncHub.publish(principal.id(), SyncKindEnum.PROGRESS, clientId);
        return ResponseEntity.noContent().build();
    }

    private static void validate(SaveProgressRequest request) {
        if (!"movie".equals(request.mediaType()) && !"tv".equals(request.mediaType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mediaType must be movie or tv");
        }
        if (request.mediaType().equals("tv") && (request.season() == null || request.episode() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "season and episode are required for tv");
        }
        if (request.progressSeconds() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "progressSeconds must be >= 0");
        }
        if (request.durationSeconds() != null && request.durationSeconds() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "durationSeconds must be > 0");
        }
    }
}
