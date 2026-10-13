package com.petcare.module.identity.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Hình thức của sửa email nhân viên (convention 06; docs/adr/0034): {@code EmailChangeRequest.newEmail} (#23) như
 * {@code email} của tạo nhân viên, ≤ 255 = {@code accounts.email}; {@code OtpCodeRequest.code} (#24) 1–8 chữ số
 * ({@code max} của {@code otp.code_length} [CFG]). Lỗi gắn đúng tên trường của contract, message tiếng Việt; mã không
 * xuất hiện trong {@code toString}.
 */
class StaffEmailChangeRequestValidationTest {

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
    void validNewEmailPasses() {
        assertThat(validator.validate(new EmailChangeRequest("moi@petcare.test"))).isEmpty();
        assertThat(validator.validate(new EmailChangeRequest("Moi.Nhan-Vien@PetCare.test"))).isEmpty();
        String local = "a".repeat(64);
        String domain = "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(59) + ".vn";
        assertThat((local + "@" + domain).length()).isEqualTo(255);
        assertThat(validator.validate(new EmailChangeRequest(local + "@" + domain))).isEmpty();
    }

    @Test
    void missingOrBlankNewEmailIsFieldErrorOnNewEmail() {
        assertThat(messages(new EmailChangeRequest(null))).containsExactly("Email mới không được để trống");
        assertThat(messages(new EmailChangeRequest("   "))).contains("Email mới không được để trống");
        violations(new EmailChangeRequest(null))
                .forEach(violation -> assertThat(violation.getPropertyPath()).hasToString("newEmail"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"khong-co-a-cong", "a@", "@petcare.test", "a b@petcare.test"})
    void malformedNewEmailIsRejected(String email) {
        assertThat(messages(new EmailChangeRequest(email))).containsExactly("Email mới không đúng định dạng");
    }

    @Test
    void newEmailOver255IsRejected() {
        String tooLong = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(60)
                + ".vn";
        assertThat(tooLong.length()).isEqualTo(256);
        assertThat(messages(new EmailChangeRequest(tooLong))).contains("Email mới tối đa 255 ký tự");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "123456", "12345678", "000000"})
    void digitCodesUpToEightPass(String code) {
        assertThat(validator.validate(new OtpCodeRequest(code))).isEmpty();
    }

    @Test
    void missingOrBlankCodeIsFieldErrorOnCode() {
        assertThat(messages(new OtpCodeRequest(null))).containsExactly("Mã OTP không được để trống");
        assertThat(messages(new OtpCodeRequest(""))).contains("Mã OTP không được để trống");
        validator.validate(new OtpCodeRequest(null))
                .forEach(violation -> assertThat(violation.getPropertyPath()).hasToString("code"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"123456789", "12a456", " 123456", "12 34", "-12345"})
    void nonDigitOrTooLongCodeIsRejected(String code) {
        assertThat(validator.validate(new OtpCodeRequest(code)).stream().map(ConstraintViolation::getMessage))
                .containsExactly("Mã OTP chỉ gồm tối đa 8 chữ số");
    }

    @Test
    void codeIsMaskedInToString() {
        assertThat(new OtpCodeRequest("654321").toString()).doesNotContain("654321").contains("***");
    }

    private Set<ConstraintViolation<EmailChangeRequest>> violations(EmailChangeRequest request) {
        return validator.validate(request);
    }

    private Set<String> messages(EmailChangeRequest request) {
        return violations(request).stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }

    private Set<String> messages(OtpCodeRequest request) {
        return validator.validate(request).stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }
}
