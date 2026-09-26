package com.fakturacnysoftver.web.ledger;

import java.util.List;

/** Chyba vstupu (400) alebo konflikt verzie (409) - s textami pre pouzivatela. */
public class LedgerException extends RuntimeException {
    private final List<String> errors;
    private final boolean conflict;

    public LedgerException(List<String> errors, boolean conflict) {
        super(String.join(" ", errors));
        this.errors = List.copyOf(errors);
        this.conflict = conflict;
    }

    public static LedgerException invalid(String... errors) {
        return new LedgerException(List.of(errors), false);
    }

    public List<String> errors() {
        return this.errors;
    }

    public boolean isConflict() {
        return this.conflict;
    }
}
