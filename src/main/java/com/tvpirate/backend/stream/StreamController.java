package com.tvpirate.backend.stream;

import java.util.List;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.tvpirate.backend.stream.StreamProvider.ResolveRequest;
import com.tvpirate.backend.stream.StreamProvider.StreamSource;
import com.tvpirate.backend.stream.dto.SourceDto;

/** The stream API: the picker list, resolve-one-provider-on-play, and the
 * token-guarded playback proxy. The proxy path is permitAll in SecurityConfig
 * — a &lt;video&gt; tag can't carry the JWT cookie, the token IS the credential.
 * vault:streaming-providers-deep-dive#architecture */
@RestController
@RequestMapping("/api/stream")
public class StreamController {

    private final StreamService streamService;
    private final StreamProxyService proxyService;

    public StreamController(StreamService streamService, StreamProxyService proxyService) {
        this.streamService = streamService;
        this.proxyService = proxyService;
    }

    /** The picker list, sorted by the service. */
    @GetMapping("/providers")
    public List<String> providers() {
        return streamService.providerNames();
    }

    /** Resolve exactly the named provider; each source comes back as a
     * proxied URL — the real one (and its referer headers) never leaves. */
    @GetMapping("/sources")
    public List<SourceDto> sources(@RequestParam String provider,
                                   @RequestParam String type,
                                   @RequestParam long tmdbId,
                                   @RequestParam(required = false) Integer season,
                                   @RequestParam(required = false) Integer episode) {
        if (!type.equals("movie") && !type.equals("tv")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be movie or tv");
        }
        if (type.equals("tv") && (season == null || episode == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "season and episode are required for tv");
        }
        List<StreamSource> resolved;
        try {
            resolved = streamService.resolve(provider,
                    new ResolveRequest(type, tmdbId, season, episode));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return resolved.stream()
                .map(source -> new SourceDto(source.quality(), source.format(),
                        "/api/stream/proxy/" + proxyService.register(source.url(), source.headers())))
                .toList();
    }

    /** Streams one proxied source. The browser's Range header passes through
     * so seeking works; the CDN's 206 answers come back untouched. */
    @GetMapping("/proxy/{token}")
    public ResponseEntity<InputStreamResource> proxy(@PathVariable String token,
                                                     @RequestHeader(value = "Range", required = false) String range) {
        return proxyService.stream(token, range);
    }
}
