package com.tvpirate.backend.stream.dto;

/** One playable source as the frontend sees it: the quality label, the
 * format the player must expect ("mp4" | "hls"), and a proxied URL that
 * hides the real upstream and its referer headers. */
public record SourceDto(String quality, String format, String proxyUrl) {
}
