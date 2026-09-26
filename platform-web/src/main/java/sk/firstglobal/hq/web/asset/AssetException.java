package sk.firstglobal.hq.web.asset;

import java.util.List;

public class AssetException extends RuntimeException {
    private final List<String> errors;

    public AssetException(List<String> errors) {
        super(String.join(" ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return this.errors;
    }
}
