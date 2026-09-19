package com.petcare.platform.exception;

<<<<<<< HEAD
import com.petcare.module.pet.exception.CaregiverInvitationConflictException;
import com.petcare.module.pet.exception.UnauthorizedDelegatedActionException;
=======
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
>>>>>>> 1c587b4 (feat: triển khai module organization)
import com.petcare.platform.model.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Mapping exception -> errorCode -> HTTP status theo đúng bảng
 * docs/convention/backend/04-exception-handling.md §4.3.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 4 handler dưới đây bắt subclass của BusinessRuleViolationException
     * trước handler cha (Spring ưu tiên @ExceptionHandler khớp type cụ thể
     * nhất) — Login/Logout/Refresh (Module 01) cần HTTP status khác 400,
     * đúng tiêu chí §4.2.2 (docs/convention/backend/04-exception-handling.md).
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        return build(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", ex.getMessage());
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ErrorResponse> handleAccountLocked(AccountLockedException ex) {
        return build(HttpStatus.LOCKED, "ACCOUNT_LOCKED", ex.getMessage());
    }

    @ExceptionHandler(AccountNotActiveException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotActive(AccountNotActiveException ex) {
        return build(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE", ex.getMessage());
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
        return build(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", ex.getMessage());
    }

    /**
     * 2 handler dưới đây bắt subclass của module pet trước handler cha —
     * cùng lý do với cụm auth ở trên (§4.2.2).
     */
    @ExceptionHandler(UnauthorizedDelegatedActionException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorizedDelegatedAction(
            UnauthorizedDelegatedActionException ex) {
        return build(HttpStatus.FORBIDDEN, "UNAUTHORIZED_DELEGATED_ACTION", ex.getMessage());
    }

    @ExceptionHandler(CaregiverInvitationConflictException.class)
    public ResponseEntity<ErrorResponse> handleCaregiverInvitationConflict(
            CaregiverInvitationConflictException ex) {
        return build(HttpStatus.CONFLICT, "CAREGIVER_INVITATION_CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    public ResponseEntity<ErrorResponse> handleBusinessRuleViolation(BusinessRuleViolationException ex) {
        return build(HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", ex.getMessage());
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidStateTransition(InvalidStateTransitionException ex) {
        return build(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedScopeException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedScope(AccessDeniedScopeException ex) {
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH", ex.getMessage());
    }

    @ExceptionHandler(ConcurrencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleConcurrencyConflict(ConcurrencyConflictException ex) {
        return build(HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT", ex.getMessage());
    }

    /**
     * Bắt AuthenticationException ném ra trong Controller/Service (hiếm — luồng
     * chính của filter chain không ném exception, xem RestAuthenticationEntryPoint).
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", ex.getMessage());
    }

    /**
     * Bắt từ chối @PreAuthorize (method-level) ném trong lúc controller đang chạy —
     * khác với từ chối ở tầng FilterSecurityInterceptor (URL-pattern), vốn xảy ra
     * trước DispatcherServlet và được RestAccessDeniedHandler xử lý riêng.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getAllErrors().stream()
                .map(error -> {
                    String field = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
                    return field + ": " + error.getDefaultMessage();
                })
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    /**
<<<<<<< HEAD
<<<<<<< HEAD
     * Lỗi ngoài dự kiến: stacktrace đi vào log kèm traceId, client chỉ nhận một
     * câu chung. Trả ex.getMessage() ra ngoài từng làm lộ nguyên câu SQL, tên
     * bảng/constraint và cả số điện thoại của tài khoản khác khi vỡ UNIQUE.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception traceId={}", MDC.get("traceId"), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "Đã xảy ra lỗi hệ thống, vui lòng thử lại sau.");
=======
=======
     * Bean Validation (`@Valid`/`MethodArgumentNotValidException`) chỉ chạy SAU khi Jackson đã
     * deserialize xong request body — 1 giá trị enum sai (vd `{"facilityType":"FOO"}`,
     * `{"role":"UNKNOWN_ROLE"}`) làm Jackson ném lỗi ngay lúc đọc JSON, trước khi `@Valid` kịp
     * chạy, nên rơi vào đây thay vì `handleValidation`. Không xử lý riêng thì exception này lọt
     * xuống {@link #handleGeneric} -> `500 INTERNAL_SERVER_ERROR`, sai bản chất (đây là lỗi input
     * của client, không phải lỗi hệ thống) — cùng nhóm `VALIDATION_FAILED`/400 với Bean Validation
     * theo đúng tinh thần bảng mapping ở đầu file.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", buildMessageNotReadableMessage(ex));
    }

    /**
     * Với nguyên nhân là {@link InvalidFormatException} nhắm vào 1 enum (case phổ biến nhất:
     * field enum sai giá trị) — trả message nêu rõ tên field + danh sách giá trị hợp lệ, cùng
     * chất lượng thông tin như lỗi Bean Validation thông thường. Các trường hợp JSON malformed
     * khác (sai cú pháp, sai kiểu không phải enum...) trả message chung, không lộ chi tiết parser
     * nội bộ.
     */
    private String buildMessageNotReadableMessage(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof InvalidFormatException invalidFormatException
                && invalidFormatException.getTargetType() != null
                && invalidFormatException.getTargetType().isEnum()) {
            String field = invalidFormatException.getPath().stream()
                    .map(reference -> reference.getFieldName())
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("."));
            String allowedValues = Arrays.stream(invalidFormatException.getTargetType().getEnumConstants())
                    .map(String::valueOf)
                    .collect(Collectors.joining(", "));
            String fieldPrefix = field.isEmpty() ? "" : field + ": ";
            return fieldPrefix + "giá trị '" + invalidFormatException.getValue()
                    + "' không hợp lệ, phải là 1 trong [" + allowedValues + "]";
        }
        return "Dữ liệu request không hợp lệ (JSON sai định dạng)";
    }

    /**
>>>>>>> 1c587b4 (feat: triển khai module organization)
     * Exception ngoài dự kiến (không map vào handler cụ thể nào ở trên) — KHÔNG
     * trả ex.getMessage() cho client (có thể lộ chi tiết nội bộ: SQL, stacktrace
     * message...). Log đầy đủ kèm traceId ở server để tra cứu khi cần.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        String traceId = MDC.get("traceId");
        log.error("Unhandled exception (traceId={})", traceId, ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "Đã xảy ra lỗi hệ thống, vui lòng thử lại sau");
>>>>>>> 8bfc5bd (feat: triển khai module iam)
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String errorCode, String message) {
        String traceId = MDC.get("traceId");
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(errorCode, message, status.value(), traceId));
    }
}
