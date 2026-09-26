package com.fakturacnysoftver.web.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Kto vykonal akciu - pre audit log a "vystavil" na fakture. */
public final class CurrentUser {
    private CurrentUser() {
    }

    public static String name(Authentication auth) {
        if (auth == null) {
            return "system";
        }
        if (auth.getPrincipal() instanceof OidcUser oidc && oidc.getEmail() != null) {
            return oidc.getEmail();
        }
        return auth.getName();
    }

    public static boolean isEditor(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + SecurityConfig.EDITOR).equals(a.getAuthority()));
    }
}
