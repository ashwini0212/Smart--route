package com.smartroute.auth;

import com.smartroute.common.error.ApiError;
import com.smartroute.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 401 and 403 raised by the security filters (before any controller runs) in the same ApiError format
 * as every other error, with the request's trace id.
 */
@Component
class ApiErrorSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    ApiErrorSecurityHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        boolean badToken = e instanceof InvalidBearerTokenException;
        // RFC 6750: tell the client which scheme is expected and, for a rejected token, that it was invalid.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, badToken ? "Bearer error=\"invalid_token\"" : "Bearer");
        write(response, ApiError.of(ErrorCode.UNAUTHORIZED,
                badToken ? "Access token is invalid or expired" : "Authentication is required",
                request.getRequestURI()));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        write(response, ApiError.of(ErrorCode.FORBIDDEN, "You do not have permission to do this", request.getRequestURI()));
    }

    private void write(HttpServletResponse response, ApiError error) throws IOException {
        response.setStatus(error.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), error);
    }
}
