package com.tvpirate.backend.api;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * One funnel for every error the API answers with. 4xx keeps the message the
 * code authored; 5xx is always the same opaque body plus an id that ties it
 * to the stack trace in the log. vault:error-handling-deep-dive#contract
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** The only thing a client is ever told about a server-side failure. */
    private static final String GENERIC_DETAIL = "Something went wrong on our side. Please try again.";

    /**
     * Anything not already an HTTP-shaped exception is an accident: 500, and
     * the details stay in the log.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        return handleExceptionInternal(ex, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    /**
     * Rethrown, not handled — Spring Security's ExceptionTranslationFilter sits
     * outside the DispatcherServlet and owns the 401/403 answer. Catching these
     * here would quietly turn every future @PreAuthorize denial into a 500.
     */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    public ResponseEntity<Object> rethrowSecurity(RuntimeException ex) {
        throw ex;
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
                                                             HttpHeaders headers, HttpStatusCode statusCode,
                                                             WebRequest request) {
        if (isCommitted(request)) {
            // The playback proxy streams its body, so a CDN dying mid-segment or a
            // browser seek aborting the connection lands here with the response
            // already on the wire. vault:error-handling-deep-dive#committed
            log.debug("response already committed, dropping {}", ex.toString());
            return null;
        }
        if (statusCode.is5xxServerError()) {
            return ResponseEntity.status(statusCode).headers(headers).body(scrubbed(ex, statusCode, request));
        }
        log.debug("{} -> {}", ex.getClass().getSimpleName(), statusCode.value());
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    /** A 5xx body with nothing in it but the status and a correlation id. */
    private ProblemDetail scrubbed(Exception ex, HttpStatusCode statusCode, WebRequest request) {
        String errorId = UUID.randomUUID().toString().substring(0, 8);
        HttpServletRequest servletRequest = servletRequest(request);
        String method = servletRequest != null ? servletRequest.getMethod() : "?";
        String path = servletRequest != null ? servletRequest.getRequestURI() : "?";

        if (isClientDisconnect(ex)) {
            // Normal on the proxy — the client hung up, so nothing failed here.
            log.debug("[{}] {} {} client disconnected", errorId, method, path);
        } else {
            log.error("[{}] {} {} answered {}", errorId, method, path, statusCode.value(), ex);
        }

        HttpStatus status = HttpStatus.resolve(statusCode.value());
        ProblemDetail problem = ProblemDetail.forStatus(statusCode);
        problem.setTitle(status != null ? status.getReasonPhrase() : "Server Error");
        problem.setDetail(GENERIC_DETAIL);
        problem.setInstance(URI.create(path));
        // The one breadcrumb that survives scrubbing: quote it in a bug report
        // and the full stack is one grep away.
        problem.setProperty("errorId", errorId);
        return problem;
    }

    /** A hung-up client, not a fault of ours — matched by name so this doesn't
     * compile against Tomcat's internals. */
    private static boolean isClientDisconnect(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t.getClass().getSimpleName().equals("ClientAbortException")
                    || t.getClass().getSimpleName().equals("AsyncRequestNotUsableException")) {
                return true;
            }
            if (t instanceof IOException && t.getMessage() != null) {
                String message = t.getMessage().toLowerCase();
                if (message.contains("broken pipe") || message.contains("connection reset")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isCommitted(WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            HttpServletResponse response = servletWebRequest.getResponse();
            return response != null && response.isCommitted();
        }
        return false;
    }

    private static HttpServletRequest servletRequest(WebRequest request) {
        return request instanceof ServletWebRequest servletWebRequest ? servletWebRequest.getRequest() : null;
    }
}
