package com.tvpirate.backend.auth.dto;

/** The token Cloudflare's Turnstile widget gave the browser; single-use, valid for 5 minutes. */
public record GuestRequest(String turnstileToken) {
}
