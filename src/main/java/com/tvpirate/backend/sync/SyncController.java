package com.tvpirate.backend.sync;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.tvpirate.backend.security.AuthedUser;

/** The live-sync line: a page opens it once and the server writes notes into it. */
@RestController
@RequestMapping("/api/events")
public class SyncController {

    private final SyncHub hub;

    public SyncController(SyncHub hub) {
        this.hub = hub;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(Authentication authentication) {
        AuthedUser principal = (AuthedUser) authentication.getPrincipal();
        return hub.subscribe(principal.id());
    }
}
