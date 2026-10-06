package com.smartroute.common.error;

import com.smartroute.common.ratelimit.RateLimitExceededException;
import com.smartroute.common.web.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Converts every exception into {@link ApiError}. Expected errors keep their message; anything
 * unexpected is logged with its stack trace and returned as a generic 500, so internals never leak.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException e, HttpServletRequest request) {
        return respond(e.code(), e.getMessage(), request, List.of());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ApiError> handleRateLimit(RateLimitExceededException e, HttpServletRequest request) {
        ResponseEntity<ApiError> response = respond(e.code(), e.getMessage(), request, List.of());
        return ResponseEntity.status(response.getStatusCode())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(response.getBody());
    }

    /** {@code @PreAuthorize} failures happen inside the controller call, so they arrive here, not at the filter. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException e, HttpServletRequest request) {
        return respond(ErrorCode.FORBIDDEN, "You do not have permission to do this", request, List.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> handleAuthentication(AuthenticationException e, HttpServletRequest request) {
        return respond(ErrorCode.UNAUTHORIZED, "Authentication is required", request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleInvalidBody(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<ApiError.FieldError> fields = e.getBindingResult().getAllErrors().stream()
                .map(error -> new ApiError.FieldError(
                        error instanceof org.springframework.validation.FieldError fe ? fe.getField() : error.getObjectName(),
                        error.getDefaultMessage()))
                .sorted(Comparator.comparing(ApiError.FieldError::field))
                .toList();
        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed", request, fields);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    ResponseEntity<ApiError> handleInvalidParameters(Exception e, HttpServletRequest request) {
        return respond(ErrorCode.VALIDATION_FAILED, "Request parameters are invalid", request, List.of());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> handleMalformed(Exception e, HttpServletRequest request) {
        return respond(ErrorCode.MALFORMED_REQUEST, "Request is malformed or has a value of the wrong type", request, List.of());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException e, HttpServletRequest request) {
        return respond(ErrorCode.CONCURRENT_MODIFICATION,
                "The resource was changed by another request; reload and try again", request, List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException e, HttpServletRequest request) {
        // Usually a unique-constraint race that the service-level check couldn't catch.
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), e.getMostSpecificCause().getMessage());
        return respond(ErrorCode.DUPLICATE_RESOURCE, "The request conflicts with existing data", request, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNoResource(NoResourceFoundException e, HttpServletRequest request) {
        return respond(ErrorCode.RESOURCE_NOT_FOUND, "No endpoint at this path", request, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        ApiError body = body(405, ErrorCode.MALFORMED_REQUEST.name(), "HTTP method not supported here", request, List.of());
        return ResponseEntity.status(405).body(body);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), e);
        return respond(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred", request, List.of());
    }

    static ResponseEntity<ApiError> respond(ErrorCode code, String message, HttpServletRequest request,
                                            List<ApiError.FieldError> fields) {
        return ResponseEntity.status(code.status())
                .body(body(code.status().value(), code.name(), message, request, fields));
    }

    private static ApiError body(int status, String code, String message, HttpServletRequest request,
                                 List<ApiError.FieldError> fields) {
        return new ApiError(Instant.now(), status, code, message, request.getRequestURI(), CorrelationId.current(), fields);
    }
}
