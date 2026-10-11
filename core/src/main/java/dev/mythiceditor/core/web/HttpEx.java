package dev.mythiceditor.core.web;

import java.util.Map;

/** Thrown by route handlers to produce a JSON error response. */
public final class HttpEx extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final int status;
    public final String code;
    public final transient Map<String, Object> extra;

    public HttpEx(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public HttpEx(int status, String code, String message, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.code = code;
        this.extra = extra;
    }
}
