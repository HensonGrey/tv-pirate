package com.tvpirate.backend.subtitle;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The subtitle endpoint: one VTT track per title/episode, resolved and
 * cached server-side so the OpenSubtitles key never reaches the browser.
 * The player lazy-loads it as a <track> element; a miss just means the
 * player runs without captions. vault:streaming-providers-deep-dive#subtitles */
@RestController
@RequestMapping("/api/subtitles")
public class SubtitleController {

    /** Language codes only — anything else is a typo'd request, not a search. */
    private static final Pattern LANG_PATTERN = Pattern.compile("[a-z]{2,3}(-[A-Za-z]{2,4})?");

    private final SubtitleService subtitleService;

    public SubtitleController(SubtitleService subtitleService) {
        this.subtitleService = subtitleService;
    }

    @GetMapping
    public ResponseEntity<byte[]> subtitles(@RequestParam String type,
                                            @RequestParam long tmdbId,
                                            @RequestParam(required = false) Integer season,
                                            @RequestParam(required = false) Integer episode,
                                            @RequestParam(defaultValue = "en") String lang) {
        if (!type.equals("movie") && !type.equals("tv")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be movie or tv");
        }
        if (type.equals("tv") && (season == null || episode == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "season and episode are required for tv");
        }
        if (!LANG_PATTERN.matcher(lang).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lang must be an ISO 639 code like en or pt-BR");
        }
        byte[] vtt = subtitleService.resolve(type, tmdbId, season, episode, lang);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "vtt", StandardCharsets.UTF_8))
                // One hour: re-resolving the same episode only touches the disk cache.
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(vtt);
    }
}
