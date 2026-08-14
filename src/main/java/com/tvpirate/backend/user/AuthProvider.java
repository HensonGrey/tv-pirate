package com.tvpirate.backend.user;

/**
 * How an account authenticates. There is deliberately no LOCAL option —
 * accounts come from a provider or exist as guests. New providers (e.g.
 * Google OAuth2) can be added here later without changing the table.
 */
public enum AuthProvider {
    GUEST,  // one-click guest account, no credentials
    GOOGLE  // reserved for future social login
}
