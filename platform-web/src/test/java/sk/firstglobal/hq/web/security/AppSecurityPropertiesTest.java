package sk.firstglobal.hq.web.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppSecurityPropertiesTest {

    @Test
    void googleModeWithoutAllowlistRefusesToStart() {
        assertThrows(IllegalStateException.class, () -> new AppSecurityProperties(
                AppSecurityProperties.Mode.GOOGLE, List.of(), List.of(" "), null, null, List.of(), null, null));
    }

    @Test
    void localModeRequiresStrongPassword() {
        assertThrows(IllegalStateException.class, () -> new AppSecurityProperties(
                AppSecurityProperties.Mode.LOCAL, null, null, "admin", "kratke", null, null, null));
        assertThrows(IllegalStateException.class, () -> new AppSecurityProperties(
                AppSecurityProperties.Mode.LOCAL, null, null, "admin", null, null, null, null));
    }

    @Test
    void allowlistMatchesEmailsAndDomainsCaseInsensitively() {
        AppSecurityProperties p = new AppSecurityProperties(AppSecurityProperties.Mode.GOOGLE,
                List.of("Pokladnik@Gmail.com"), List.of("firstglobal.sk"), null, null, List.of("pokladnik@gmail.com"), null, null);

        assertTrue(p.isAllowed("pokladnik@gmail.com"));
        assertTrue(p.isAllowed("clen@FirstGlobal.sk"));
        assertFalse(p.isAllowed("clen@firstglobal.sk.evil.com"));
        assertFalse(p.isAllowed("iny@gmail.com"));
        assertFalse(p.isAllowed(null));
        assertTrue(p.isEditor("POKLADNIK@gmail.com"));
        assertFalse(p.isEditor("clen@firstglobal.sk"), "člen z domény len číta");
        AppSecurityProperties onlyEditors = new AppSecurityProperties(AppSecurityProperties.Mode.GOOGLE,
                null, null, null, null, List.of("predseda@example.sk"), null, null);
        assertTrue(onlyEditors.isAllowed("predseda@example.sk"), "editor má prístup aj bez APP_ALLOWED_EMAILS");
    }

    @Test
    void googleModeWithoutEditorRefusesToStart() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new AppSecurityProperties(
                AppSecurityProperties.Mode.GOOGLE, List.of("a@b.sk"), null, null, null, List.of(), null, null));
        assertTrue(e.getMessage().contains("APP_EDITOR_EMAILS"));
    }
}
