package dev.mythiceditor.core.storage;

import java.util.Map;

/** Error with a machine readable code and a message that is safe to show to the user. */
public final class StoreException extends Exception {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final int status;
    private final transient Map<String, Object> extra;

    public StoreException(String code, int status, String message) {
        this(code, status, message, null, Map.of());
    }

    public StoreException(String code, int status, String message, Throwable cause, Map<String, Object> extra) {
        super(message, cause);
        this.code = code;
        this.status = status;
        this.extra = extra;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public Map<String, Object> extra() {
        return extra;
    }
}
