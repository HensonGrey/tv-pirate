package com.tvpirate.backend.theme;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.tvpirate.backend.security.AuthedUser;
import com.tvpirate.backend.sync.SyncHub;
import com.tvpirate.backend.sync.SyncKindEnum;
import com.tvpirate.backend.theme.dto.ThemeDto;

/** The light/dark toggle's server side. A save tells the user's other tabs and devices,
 * which then GET the new choice. */
@RestController
@RequestMapping("/api/theme")
public class ThemeController {

    private final ThemeService themeService;

    private final SyncHub syncHub;

    public ThemeController(ThemeService themeService, SyncHub syncHub) {
        this.themeService = themeService;
        this.syncHub = syncHub;
    }

    @GetMapping
    public ThemeDto get(Authentication authentication) {
        AuthedUser principal = (AuthedUser) authentication.getPrincipal();
        return new ThemeDto(themeService.find(principal.id()).orElse(null));
    }

    @PutMapping
    public ResponseEntity<Void> save(@RequestBody ThemeDto request,
                                     @RequestHeader(name = SyncHub.CLIENT_ID_HEADER, required = false) String clientId,
                                     Authentication authentication) {
        if (!"light".equals(request.theme()) && !"dark".equals(request.theme())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "theme must be light or dark");
        }
        AuthedUser principal = (AuthedUser) authentication.getPrincipal();
        themeService.save(principal.id(), request.theme());
        syncHub.publish(principal.id(), SyncKindEnum.THEME, clientId);
        return ResponseEntity.noContent().build();
    }
}
