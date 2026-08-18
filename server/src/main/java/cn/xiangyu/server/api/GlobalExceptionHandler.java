package cn.xiangyu.server.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException exception, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        if (exception.retryAfterSeconds() != null) {
            headers.set(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds().toString());
        }
        return new ResponseEntity<>(body(exception.code(), exception.getMessage(), request,
                exception.retryAfterSeconds()), headers, exception.status());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            ConstraintViolationException.class})
    ResponseEntity<Map<String, Object>> invalidRequest(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(body("INVALID_REQUEST", "请求内容格式不正确", request, null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unhandled request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL_ERROR", "服务暂时不可用，请稍后重试", request, null));
    }

    public static Map<String, Object> body(String code, String message, HttpServletRequest request,
                                           Integer retryAfterSeconds) {
        String requestId = String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE));
        Map<String, Object> error = new java.util.LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("requestId", requestId);
        if (retryAfterSeconds != null) error.put("retryAfterSeconds", retryAfterSeconds);
        error.put("details", Map.of());
        return Map.of("error", error);
    }
}
