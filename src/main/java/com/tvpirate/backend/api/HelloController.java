package com.tvpirate.backend.api;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints used to verify the auth flow end-to-end.
 */
@RestController
public class HelloController {

    /** Protected: requires a valid JWT (enforced by SecurityConfig). */
    @GetMapping("/api/hello")
    public String hello(Authentication authentication) {
        return "Hello, " + authentication.getName() + "! Your JWT works.";
    }

    /** Public: no token needed. */
    @GetMapping("/api/public/hello")
    public String publicHello() {
        return "Hello from the public endpoint — no JWT needed.";
    }
}
