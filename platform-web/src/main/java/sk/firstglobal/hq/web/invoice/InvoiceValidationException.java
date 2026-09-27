package sk.firstglobal.hq.web.invoice;

import java.util.List;

public class InvoiceValidationException extends RuntimeException {
    private final List<String> errors;

    public InvoiceValidationException(List<String> errors) {
        super(String.join(" ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return this.errors;
    }
}
