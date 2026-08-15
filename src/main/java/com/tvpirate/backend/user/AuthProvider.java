package com.tvpirate.backend.user;

/** How an account authenticates — deliberately no LOCAL option: accounts
 * come from a provider or exist as guests. New providers add entries here
 * without table changes. vault:auth-deep-dive#user-model */
public enum AuthProvider {
    GUEST,  // one-click guest account, no credentials
    GOOGLE  // reserved for future social login
}
