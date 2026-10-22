package com.petcare.module.customer.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class CustomerRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void start() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void stop() {
        factory.close();
    }

    private static <T> List<String> invalid(T value) {
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        return violations.stream().map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted()
                .collect(Collectors.toList());
    }

    // ---------------------------------------------------------------- tạo

    @Test
    void createAcceptsTenAndElevenDigitPhones() {
        assertThat(invalid(new CreateCounterCustomerRequest("Lan", "0901234567", null))).isEmpty();
        assertThat(invalid(new CreateCounterCustomerRequest("Lan", "09012345678", "a@x.vn"))).isEmpty();
    }

    @Test
    void createRequiresNameAndPhone() {
        assertThat(invalid(new CreateCounterCustomerRequest(null, null, null))).hasSize(2);
        assertThat(invalid(new CreateCounterCustomerRequest("  ", "0901234567", null)))
                .containsExactly("fullName: Vui lòng nhập họ tên");
    }

    @Test
    void createRejectsBadPhoneEmailAndLongName() {
        for (String phone : new String[] {"901234567", "0901234", "090123456789", "+84901234567", "090 123 4567"}) {
            assertThat(invalid(new CreateCounterCustomerRequest("Lan", phone, null))).as(phone).isNotEmpty();
        }
        assertThat(invalid(new CreateCounterCustomerRequest("Lan", "0901234567", "khong-phai-email"))).hasSize(1);
        assertThat(invalid(new CreateCounterCustomerRequest("x".repeat(101), "0901234567", null))).hasSize(1);
        assertThat(invalid(new CreateCounterCustomerRequest("Lan", "0901234567", "a".repeat(250) + "@x.vn")))
                .isNotEmpty();
    }

    // ---------------------------------------------------------------- sửa

    @Test
    void updateAllNullIsValid() {
        assertThat(invalid(new UpdateCustomerRequest(null, null, null))).isEmpty();
    }

    @Test
    void updateEmptyPhoneAndEmailMeanClear() {
        assertThat(invalid(new UpdateCustomerRequest(null, "", ""))).isEmpty();
        assertThat(invalid(new UpdateCustomerRequest(null, "   ", null))).isEmpty();
    }

    @Test
    void updateBlankNameIsRejectedButNullIsKept() {
        assertThat(invalid(new UpdateCustomerRequest("   ", null, null)))
                .containsExactly("fullName: Họ tên không được để trống");
        assertThat(invalid(new UpdateCustomerRequest("", null, null))).isNotEmpty();
    }

    @Test
    void updateRejectsBadPhoneAndEmail() {
        assertThat(invalid(new UpdateCustomerRequest(null, "12345", null))).hasSize(1);
        assertThat(invalid(new UpdateCustomerRequest(null, null, "bad"))).hasSize(1);
    }

    // ---------------------------------------------------------------- tra cứu

    @Test
    void searchNeedsAtLeastOneCondition() {
        assertThat(invalid(new CustomerSearchFilter(null, null, null, null))).hasSize(1);
        assertThat(invalid(new CustomerSearchFilter("", "  ", "", " "))).hasSize(1);
        assertThat(invalid(new CustomerSearchFilter(null, null, null, "Mực"))).isEmpty();
        assertThat(invalid(new CustomerSearchFilter("0901234567", null, null, null))).isEmpty();
    }

    @Test
    void searchTrimsAndLowercasesEmailOnly() {
        CustomerSearchFilter filter = new CustomerSearchFilter(" 0901234567 ", "  Lan ", " LAN@X.vn ", " Mực ");

        assertThat(filter.phone()).isEqualTo("0901234567");
        assertThat(filter.name()).isEqualTo("Lan");
        assertThat(filter.email()).isEqualTo("lan@x.vn");
        assertThat(filter.petName()).isEqualTo("Mực");
    }
}
