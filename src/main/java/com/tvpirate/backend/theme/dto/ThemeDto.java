package com.tvpirate.backend.theme.dto;

/** "light" or "dark"; null in a GET when the user never picked one. */
public record ThemeDto(String theme) {
}
