package io.github.ewcastroh.promptbattle.web;

import io.github.ewcastroh.promptbattle.game.ErrorCode;
import io.github.ewcastroh.promptbattle.game.GameException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every error is an RFC 9457 ProblemDetail with a machine-readable {@code code} and optional {@code params}
 * (API.md §2). {@code detail} is for developers only; it never contains secrets or prompt text.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static ResponseEntity<ProblemDetail> problem(HttpServletRequest request, int status, ErrorCode code,
            String detail, Map<String, Object> params) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail);
        body.setInstance(URI.create(request.getRequestURI()));
        body.setProperty("code", code.name());
        if (params != null && !params.isEmpty()) {
            body.setProperty("params", params);
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (code == ErrorCode.RATE_LIMITED && params != null && params.get("retryAfterSec") != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(params.get("retryAfterSec")));
        }
        return builder.body(body);
    }

    static ResponseEntity<ProblemDetail> problem(HttpServletRequest request, ErrorCode code, String detail,
            Map<String, Object> params) {
        return problem(request, code.httpStatus(), code, detail, params);
    }

    @ExceptionHandler(GameException.class)
    ResponseEntity<ProblemDetail> game(GameException e, HttpServletRequest request) {
        return problem(request, e.code(), e.getMessage(), e.params());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpServletRequest request) {
        return problem(request, ErrorCode.VALIDATION_FAILED, "Malformed request body", Map.of("field", "body"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> missingParameter(MissingServletRequestParameterException e,
            HttpServletRequest request) {
        return problem(request, ErrorCode.VALIDATION_FAILED, "Missing parameter",
                Map.of("field", e.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return problem(request, ErrorCode.VALIDATION_FAILED, "Invalid parameter", Map.of("field", e.getName()));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ProblemDetail> missingPart(MissingServletRequestPartException e, HttpServletRequest request) {
        if ("target".equals(e.getRequestPartName())) {
            return problem(request, ErrorCode.UPLOAD_INVALID, "Missing target", Map.of("maxMb", 10));
        }
        return problem(request, ErrorCode.VALIDATION_FAILED, "Missing part", Map.of("field", e.getRequestPartName()));
    }

    @ExceptionHandler({ MaxUploadSizeExceededException.class, MultipartException.class })
    ResponseEntity<ProblemDetail> upload(HttpServletRequest request) {
        return problem(request, ErrorCode.UPLOAD_INVALID, "Invalid upload", Map.of("maxMb", 10));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> mediaType(HttpServletRequest request) {
        return problem(request, ErrorCode.VALIDATION_FAILED, "Unsupported content type",
                Map.of("field", "Content-Type"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> notFound(HttpServletRequest request) {
        return problem(request, ErrorCode.NOT_FOUND, "Not found", Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> method(HttpServletRequest request) {
        return problem(request, 405, ErrorCode.NOT_FOUND, "Method not allowed", Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unexpected error on {} {}", request.getMethod(), request.getRequestURI(), e);
        return problem(request, ErrorCode.INTERNAL_ERROR, "Unexpected error", Map.of());
    }
}
