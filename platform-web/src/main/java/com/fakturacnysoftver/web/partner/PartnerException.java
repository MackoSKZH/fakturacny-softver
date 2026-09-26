package com.fakturacnysoftver.web.partner;

import java.util.List;

public class PartnerException extends RuntimeException {
    private final List<String> errors;

    public PartnerException(List<String> errors) {
        super(String.join(" ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return this.errors;
    }
}
