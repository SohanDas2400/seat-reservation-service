package com.seatreserve.security;

import com.seatreserve.web.UnauthorizedException;

/**
 * Holds the authenticated user id for the current request thread (Tomcat is thread-per-request).
 * Set by {@link JwtAuthFilter}, read by controllers. Identity is ALWAYS token-derived — never
 * taken from the request body.
 */
public final class UserContext {

    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(String userId) {
        CURRENT_USER.set(userId);
    }

    public static void clear() {
        CURRENT_USER.remove();
    }

    public static String currentUserId() {
        return CURRENT_USER.get();
    }

    /** Returns the authenticated user id or throws 401 if the request carried no valid token. */
    public static String requireUserId() {
        String uid = CURRENT_USER.get();
        if (uid == null || uid.isBlank()) {
            throw new UnauthorizedException("missing or invalid bearer token");
        }
        return uid;
    }
}
