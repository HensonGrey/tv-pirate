package com.tvpirate.backend.sync;

import java.util.Locale;

/** What changed. The browser listens for each by its lower-case name. */
public enum SyncKindEnum {
    FAVOURITES,
    PROGRESS;

    String eventName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
