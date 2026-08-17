package com.tvpirate.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled — the daily guest cleanup sweep depends on it. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
