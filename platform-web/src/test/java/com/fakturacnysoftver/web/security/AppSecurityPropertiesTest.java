package com.fakturacnysoftver.web.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppSecurityPropertiesTest {

    @Test
    void googleModeWithoutAllowlistRefusesToStart() {
        assertThrows(IllegalStateException.class, () -> new AppSecurityProperties(
                AppSecurityProperties.Mode.GOOGLE, List.of(), List.of(" "), null, null));
    }

    @Test
    void localModeRequiresStrongPassword() {
        assertThrows(IllegalStateException.class, () -> new AppSecurityProperties(
                AppSecurityProperties.Mode.LOCAL, null, null, "admin", "kratke"));
        assertThrows(IllegalStateException.class, () -> new AppSecurityProperties(
                AppSecurityProperties.Mode.LOCAL, null, null, "admin", null));
    }

    @Test
    void allowlistMatchesEmailsAndDomainsCaseInsensitively() {
        AppSecurityProperties p = new AppSecurityProperties(AppSecurityProperties.Mode.GOOGLE,
                List.of("Pokladnik@Gmail.com"), List.of("firstglobal.sk"), null, null);

        assertTrue(p.isAllowed("pokladnik@gmail.com"));
        assertTrue(p.isAllowed("clen@FirstGlobal.sk"));
        assertFalse(p.isAllowed("clen@firstglobal.sk.evil.com"));
        assertFalse(p.isAllowed("iny@gmail.com"));
        assertFalse(p.isAllowed(null));
    }
}
