package com.petcare.platform.exception;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.petcare.platform.model.ErrorResponse;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import lombok.extern.slf4j.Slf4j;

/**
 * Nơi duy nhất tạo {@link ErrorResponse} (docs/convention/backend/04-exception-handling.md §4.3).
 * Lỗi từ tầng security (401/403) cũng được chuyển về đây qua {@code HandlerExceptionResolver}.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    static final String CONFLICT_MESSAGE =
            "Dữ liệu vừa bị thay đổi bởi thao tác khác hoặc xung đột với dữ liệu hiện có, vui lòng tải lại và thử lại";
    static final String INTERNAL_MESSAGE = "Lỗi hệ thống, vui lòng thử lại sau";

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ErrorResponse> handlePlatform(PlatformException ex) {
        log.warn("{}: {}", ex.errorCode(), ex.getMessage());
        return build(ex.errorCode(), ex.clientMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException ex) {
        List<String> details = new ArrayList<>();
        for (ObjectError error : ex.getBindingResult().getAllErrors()) {
            details.add(describe(error.getObjectName(), error));
        }
        return validationFailed(details);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException ex) {
        List<String> details = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String parameter = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                details.add(describe(parameter, error));
            }
        });
        return validationFailed(details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<String> details = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            details.add(lastNodeName(violation.getPropertyPath()) + ": " + violation.getMessage());
        }
        return validationFailed(details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("{}: {}", ErrorCode.MALFORMED_REQUEST, ex.getMessage());
        return build(ErrorCode.MALFORMED_REQUEST, "Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("{}: {}", ErrorCode.MALFORMED_REQUEST, ex.getMessage());
        return build(ErrorCode.MALFORMED_REQUEST, "Tham số '" + ex.getName() + "' sai kiểu dữ liệu");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
        log.warn("{}: {}", ErrorCode.MALFORMED_REQUEST, ex.getMessage());
        return build(ErrorCode.MALFORMED_REQUEST, "Thiếu tham số '" + ex.getParameterName() + "'");
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNoResource(Exception ex) {
        return build(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy đường dẫn yêu cầu");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, "Phương thức " + ex.getMethod() + " không được hỗ trợ");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Kiểu nội dung không được hỗ trợ");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
        return build(ErrorCode.UNAUTHENTICATED, "Chưa đăng nhập hoặc phiên đăng nhập đã hết hạn");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        log.warn("{}: {}", ErrorCode.ACCESS_DENIED, ex.getMessage());
        return build(ErrorCode.ACCESS_DENIED, "Không có quyền thực hiện thao tác này");
    }

    /** Khóa bi quan/lạc quan thất bại, hoặc vi phạm ràng buộc DB do hai thao tác tranh cùng dữ liệu. */
    @ExceptionHandler({
            PessimisticLockingFailureException.class,
            OptimisticLockingFailureException.class,
            DataIntegrityViolationException.class})
    public ResponseEntity<ErrorResponse> handleDataConflict(DataAccessException ex) {
        log.warn("{}: {}", ErrorCode.CONCURRENCY_CONFLICT, ex.getMessage());
        return build(ErrorCode.CONCURRENCY_CONFLICT, CONFLICT_MESSAGE);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        // Lỗi web khác của Spring (406, 413, ResponseStatusException...) mang sẵn status: giữ nhóm 4xx là lỗi request.
        if (ex instanceof org.springframework.web.ErrorResponse springError) {
            HttpStatusCode status = springError.getStatusCode();
            if (status.is4xxClientError()) {
                ErrorCode errorCode = clientErrorCode(status);
                log.warn("{} ({}): {}", errorCode, status.value(), ex.getMessage());
                return build(errorCode, "Request không hợp lệ");
            }
        }
        log.error("Unexpected error", ex);
        return build(ErrorCode.INTERNAL_ERROR, INTERNAL_MESSAGE);
    }

    private static ErrorCode clientErrorCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.ACCESS_DENIED;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> ErrorCode.MALFORMED_REQUEST;
        };
    }

    private ResponseEntity<ErrorResponse> validationFailed(List<String> details) {
        String message = String.join("; ", details);
        log.warn("{}: {}", ErrorCode.VALIDATION_FAILED, message);
        return build(ErrorCode.VALIDATION_FAILED, message);
    }

    private static String describe(String fallbackName, MessageSourceResolvable error) {
        String name = error instanceof FieldError fieldError ? fieldError.getField() : fallbackName;
        return name + ": " + error.getDefaultMessage();
    }

    private static String lastNodeName(Path path) {
        String name = null;
        for (Path.Node node : path) {
            name = node.getName();
        }
        return name == null ? path.toString() : name;
    }

    private static ResponseEntity<ErrorResponse> build(ErrorCode errorCode, String message) {
        return ResponseEntity.status(errorCode.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(errorCode, message));
    }
}
