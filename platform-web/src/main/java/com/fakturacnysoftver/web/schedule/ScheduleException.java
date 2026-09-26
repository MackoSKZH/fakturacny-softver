package com.fakturacnysoftver.web.schedule;

import java.util.List;

public class ScheduleException extends RuntimeException {
    private final List<String> errors;

    public ScheduleException(List<String> errors) {
        super(String.join(" ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return this.errors;
    }
}
