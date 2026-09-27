package sk.firstglobal.hq.web.access;

import java.util.List;

public class AccessException extends RuntimeException {
    private final List<String> errors;

    public AccessException(List<String> errors) {
        super(String.join(" ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return this.errors;
    }
}
