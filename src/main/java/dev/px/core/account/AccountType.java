package dev.px.core.account;

/** How an account authenticates. */
public enum AccountType {

    /** A real account, authenticated through the platform's OAuth flow. */
    MICROSOFT,

    /** A name with no authentication, for offline and LAN play. */
    OFFLINE
}
