package com.petcare.platform.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.petcare.platform.enums.FacilityType;
import com.petcare.platform.model.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/convention/backend/04-exception-handling.md — Jackson ném
 * {@link HttpMessageNotReadableException} khi deserialize request body thất bại (vd giá trị
 * enum sai như {@code CreateStoreRequest.facilityType}), TRƯỚC khi Bean Validation
 * ({@code @Valid}/{@link org.springframework.web.bind.MethodArgumentNotValidException}) kịp
 * chạy — phải map về cùng {@code VALIDATION_FAILED}/400 như Bean Validation, không được lọt
 * xuống {@code handleGeneric} (500).
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleMessageNotReadable_invalidEnumValue_returns400WithFieldAndAllowedValues() {
        InvalidFormatException cause = InvalidFormatException.from(null, "Cannot deserialize value",
                "FOO", FacilityType.class);
        cause.prependPath(Object.class, "facilityType");
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("JSON parse error", cause, null);

        ResponseEntity<ErrorResponse> response = handler.handleMessageNotReadable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().message())
                .contains("facilityType")
                .contains("FOO")
                .contains("RETAIL_STORE")
                .contains("CENTRAL_WAREHOUSE");
    }

    @Test
    void handleMessageNotReadable_genericMalformedJson_returns400WithGenericMessage() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("Unexpected end-of-input", (Throwable) null, null);

        ResponseEntity<ErrorResponse> response = handler.handleMessageNotReadable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().message()).isNotBlank();
    }
}
