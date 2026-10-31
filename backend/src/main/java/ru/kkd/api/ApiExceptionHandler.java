package ru.kkd.api;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import ru.kkd.application.ResourceNotFoundException;
import ru.kkd.application.RunNotCompletedException;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Object> invalidRequest(InvalidRequestException exception) {
        return response(422, "INVALID_PARAMETER", exception.getMessage(), HttpHeaders.EMPTY);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Object> notFound(ResourceNotFoundException exception) {
        return response(
                404,
                exception.resource().toUpperCase(Locale.ROOT) + "_NOT_FOUND",
                exception.getMessage(),
                HttpHeaders.EMPTY);
    }

    @ExceptionHandler(RunNotCompletedException.class)
    public ResponseEntity<Object> incompleteRun(RunNotCompletedException exception) {
        return response(422, "RUN_NOT_COMPLETED", exception.getMessage(), HttpHeaders.EMPTY);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception exception) {
        LOG.error("Unexpected API error ({})", exception.getClass().getSimpleName());
        return response(500, "INTERNAL_ERROR", "Internal server error", HttpHeaders.EMPTY);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        int code = status.value() == 400 ? 422 : status.value();
        String errorCode =
                switch (code) {
                    case 404 -> "NOT_FOUND";
                    case 405 -> "METHOD_NOT_ALLOWED";
                    case 406 -> "NOT_ACCEPTABLE";
                    case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                    case 422 -> "INVALID_PARAMETER";
                    default -> code >= 500 ? "INTERNAL_ERROR" : "HTTP_ERROR";
                };
        HttpStatus known = HttpStatus.resolve(code);
        String message =
                code >= 500
                        ? "Internal server error"
                        : known == null ? "Request failed" : known.getReasonPhrase();
        return response(code, errorCode, message, headers);
    }

    private ResponseEntity<Object> response(
            int status, String code, String message, HttpHeaders originalHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(originalHeaders);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(
                new ApiResponses.ErrorResponse(new ApiResponses.ErrorBody(code, message)),
                headers,
                HttpStatusCode.valueOf(status));
    }
}
