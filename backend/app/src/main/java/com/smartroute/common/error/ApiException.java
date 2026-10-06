package com.smartroute.common.error;

/**
 * Base for expected business errors. The message is shown to API clients, so it must never contain
 * internal details (SQL, class names, stack traces).
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static ApiException notFound(String resource, Object id) {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, resource + " " + id + " was not found");
    }

    public static ApiException duplicate(String message) {
        return new ApiException(ErrorCode.DUPLICATE_RESOURCE, message);
    }

    public static ApiException businessRule(String message) {
        return new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, message);
    }
}
