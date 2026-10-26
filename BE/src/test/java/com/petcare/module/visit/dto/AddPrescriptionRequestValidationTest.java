package com.petcare.module.visit.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Hình thức body của visit-v1 #16 {@code AddPrescriptionRequest} (docs/adr/0077): thuốc, số lượng, liều bắt buộc; số
 * lượng > 0; liều bỏ khoảng trắng hai đầu (cả Unicode) rồi mới kiểm trống / tối đa 500; {@code shortageResolution} tùy
 * chọn; message tiếng Việt. Kiểu sai (số thực, enum lạ) là 400 {@code MALFORMED_REQUEST} ở Jackson — xem
 * {@code PrescriptionIT}.
 */
class AddPrescriptionRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void open() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @Test
    void aValidBodyHasNoViolationAndTheDosageIsStripped() {
        AddPrescriptionRequest request = new AddPrescriptionRequest(77L, 10, "　 Ngày 2 lần \n", null);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.dosageInstructions()).isEqualTo("Ngày 2 lần");
        assertThat(request.shortageResolution()).isNull();
    }

    @Test
    void bothResolutionsAreAccepted() {
        assertThat(validator.validate(new AddPrescriptionRequest(77L, 10, "x", ShortageResolution.REDUCE_TO_AVAILABLE)))
                .isEmpty();
        assertThat(validator.validate(new AddPrescriptionRequest(77L, 10, "x", ShortageResolution.EXTERNAL_PURCHASE)))
                .isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -3})
    void productIdIsRequiredAndPositive(Long productId) {
        assertThat(messages(new AddPrescriptionRequest(productId, 1, "x", null)))
                .containsExactly(productId == null ? "Thiếu thuốc" : "Mã thuốc không hợp lệ");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void quantityIsRequiredAndPositive(Integer quantity) {
        assertThat(messages(new AddPrescriptionRequest(77L, quantity, "x", null)))
                .containsExactly(quantity == null ? "Thiếu số lượng" : "Số lượng phải lớn hơn 0");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "　\t\n"})
    void dosageIsRequiredEvenWhenItIsOnlyWhitespace(String dosage) {
        assertThat(messages(new AddPrescriptionRequest(77L, 1, dosage, null))).containsExactly("Bắt buộc nhập liều dùng");
    }

    @Test
    void dosageIsAtMost500CharactersAfterStripping() {
        assertThat(messages(new AddPrescriptionRequest(77L, 1, " " + "x".repeat(500) + " ", null))).isEmpty();
        assertThat(messages(new AddPrescriptionRequest(77L, 1, "x".repeat(501), null)))
                .containsExactly("Liều dùng tối đa 500 ký tự");
    }

    @Test
    void everyMissingFieldIsReportedAtOnce() {
        assertThat(messages(new AddPrescriptionRequest(null, null, null, null)))
                .containsExactlyInAnyOrder("Thiếu thuốc", "Thiếu số lượng", "Bắt buộc nhập liều dùng");
    }

    private static Set<String> messages(AddPrescriptionRequest request) {
        return validator.validate(request).stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }
}
