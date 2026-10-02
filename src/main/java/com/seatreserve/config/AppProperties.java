package com.seatreserve.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application configuration bound from the {@code app.*} properties.
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** HS256 signing secret for user JWTs (>= 32 bytes). Supplied via JWT_SECRET in prod. */
    private String jwtSecret;

    /** Shared secret required (header X-Admin-Token) to create shows. */
    private String adminToken;

    /** How long a reserved hold lives before it auto-expires back to available. */
    private long holdTtlSeconds = 120;

    /** Default per-user seat limit for a show when the create request omits it. */
    private int defaultPerUserLimit = 4;

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public String getAdminToken() {
        return adminToken;
    }

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken;
    }

    public long getHoldTtlSeconds() {
        return holdTtlSeconds;
    }

    public void setHoldTtlSeconds(long holdTtlSeconds) {
        this.holdTtlSeconds = holdTtlSeconds;
    }

    public int getDefaultPerUserLimit() {
        return defaultPerUserLimit;
    }

    public void setDefaultPerUserLimit(int defaultPerUserLimit) {
        this.defaultPerUserLimit = defaultPerUserLimit;
    }
}
