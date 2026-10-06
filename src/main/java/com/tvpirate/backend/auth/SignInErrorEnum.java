package com.tvpirate.backend.auth;

/** Why a redirect sign-in bounced back to /login; sent as ?signInError=<lowercase name>,
 * a code rather than text so a crafted link can't put arbitrary words on our page. */
public enum SignInErrorEnum {
    CANCELLED,   // the user backed out on the provider's consent screen
    FAILED,      // state mismatch, or the provider refused the code
    UNAVAILABLE; // this server has no credentials for the provider

    public String queryValue() {
        return name().toLowerCase();
    }
}
