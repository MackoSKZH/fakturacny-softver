package com.fakturacnysoftver.web.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * Nastavenia prihlasovania. Aplikacia sa radsej nespusti, nez aby bezala otvorena:
 * v rezime GOOGLE musi byt zoznam povolenych e-mailov alebo domen, v rezime LOCAL silne heslo.
 */
@ConfigurationProperties("app.security")
public record AppSecurityProperties(
        Mode mode,
        List<String> allowedEmails,
        List<String> allowedDomains,
        String localUsername,
        String localPassword,
        List<String> editorEmails,
        List<String> localReaders) {

    public static final int MIN_PASSWORD_LENGTH = 12;

    public enum Mode { GOOGLE, LOCAL }

    public AppSecurityProperties {
        mode = mode == null ? Mode.LOCAL : mode;
        allowedEmails = normalize(allowedEmails);
        allowedDomains = normalize(allowedDomains);
        localUsername = localUsername == null || localUsername.isBlank() ? "admin" : localUsername.trim();
        editorEmails = normalize(editorEmails);
        localReaders = normalize(localReaders);

        if (mode == Mode.GOOGLE && allowedEmails.isEmpty() && allowedDomains.isEmpty() && editorEmails.isEmpty()) {
            throw new IllegalStateException("Režim GOOGLE bez APP_ALLOWED_EMAILS / APP_ALLOWED_DOMAINS "
                    + "by pustil dnu ktorýkoľvek Google účet. Nastavte zoznam povolených účtov.");
        }
        if (mode == Mode.GOOGLE && editorEmails.isEmpty()) {
            throw new IllegalStateException("Nastavte APP_EDITOR_EMAILS - aspoň jeden účet, ktorý smie "
                    + "vystavovať faktúry. Ostatní povolení členovia majú prístup len na čítanie.");
        }
        if (mode == Mode.LOCAL && (localPassword == null || localPassword.length() < MIN_PASSWORD_LENGTH)) {
            throw new IllegalStateException("Režim LOCAL vyžaduje APP_LOCAL_PASSWORD s aspoň "
                    + MIN_PASSWORD_LENGTH + " znakmi.");
        }
    }

    public boolean isAllowed(String email) {
        if (email == null) {
            return false;
        }
        String e = email.trim().toLowerCase(Locale.ROOT);
        int at = e.lastIndexOf('@');
        return this.allowedEmails.contains(e) || this.editorEmails.contains(e)
                || (at > 0 && this.allowedDomains.contains(e.substring(at + 1)));
    }

    /** Editor smie vystavovat doklady a menit udaje; ostatni povoleni len citaju. */
    public boolean isEditor(String email) {
        return email != null && this.editorEmails.contains(email.trim().toLowerCase(Locale.ROOT));
    }

    private static List<String> normalize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(v -> v.trim().toLowerCase(Locale.ROOT))
                .toList();
    }
}
