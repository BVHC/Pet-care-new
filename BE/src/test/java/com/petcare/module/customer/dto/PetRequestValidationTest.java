package com.petcare.module.customer.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.petcare.module.customer.api.Species;
import com.petcare.module.customer.entity.PetSex;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class PetRequestValidationTest {

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
        return validator.validate(value).stream().map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted()
                .collect(Collectors.toList());
    }

    private static CreatePetRequest create(String name, Species species, PetSex sex, String photoUrl) {
        return new CreatePetRequest(name, species, sex, null, null, null, null, null, photoUrl);
    }

    @Test
    void createNeedsNameSpeciesAndSex() {
        assertThat(invalid(create("Mực", Species.DOG, PetSex.MALE, null))).isEmpty();
        assertThat(invalid(create(null, null, null, null))).hasSize(3);
        assertThat(invalid(create("  ", Species.DOG, PetSex.MALE, null)))
                .containsExactly("name: Vui lòng nhập tên thú cưng");
    }

    @Test
    void createLimitsLengths() {
        assertThat(invalid(create("x".repeat(101), Species.DOG, PetSex.MALE, null))).hasSize(1);
        assertThat(invalid(new CreatePetRequest("Mực", Species.DOG, PetSex.MALE, "b".repeat(101), null, null,
                "c".repeat(51), null, null))).hasSize(2);
    }

    @Test
    void photoMustBeHttpsWhenSent() {
        assertThat(invalid(create("Mực", Species.DOG, PetSex.MALE, "https://cdn.petcare.vn/a.jpg"))).isEmpty();
        assertThat(invalid(create("Mực", Species.DOG, PetSex.MALE, "http://cdn.petcare.vn/a.jpg"))).hasSize(1);
        assertThat(invalid(create("Mực", Species.DOG, PetSex.MALE, "javascript:alert(1)"))).hasSize(1);
        assertThat(invalid(create("Mực", Species.DOG, PetSex.MALE, "https://" + "a".repeat(500)))).isNotEmpty();
    }

    @Test
    void updateAllNullIsValidAndEmptyOptionalsMeanClear() {
        assertThat(invalid(new UpdatePetRequest(null, null, null, null, null, null, null, null, null))).isEmpty();
        assertThat(invalid(new UpdatePetRequest(null, null, null, "", null, null, "", null, ""))).isEmpty();
    }

    @Test
    void updateNameCannotBeBlank() {
        assertThat(invalid(new UpdatePetRequest("  ", null, null, null, null, null, null, null, null)))
                .containsExactly("name: Tên thú cưng không được để trống");
        assertThat(invalid(new UpdatePetRequest("", null, null, null, null, null, null, null, null))).isNotEmpty();
    }

    @Test
    void weightIsRequiredAndFitsNumeric62() {
        assertThat(invalid(new AddWeightRequest(null, null))).hasSize(1);
        assertThat(invalid(new AddWeightRequest(new BigDecimal("9999.99"), null))).isEmpty();
        assertThat(invalid(new AddWeightRequest(new BigDecimal("10000"), null))).hasSize(1);
        assertThat(invalid(new AddWeightRequest(new BigDecimal("1.234"), null))).hasSize(1);
    }

    @Test
    void nonPositiveWeightPassesValidationSoTheServiceCanAnswerBrKh04() {
        assertThat(invalid(new AddWeightRequest(BigDecimal.ZERO, null))).isEmpty();
        assertThat(invalid(new AddWeightRequest(new BigDecimal("-1"), null))).isEmpty();
    }

    // ---------------------------------------------------------------- đã mất, chuyển chủ

    @Test
    void markDeceasedNeedsDateAndExplicitConfirmation() {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 9, 30);
        assertThat(invalid(new MarkDeceasedRequest(day, true))).isEmpty();
        assertThat(invalid(new MarkDeceasedRequest(null, true))).containsExactly("deceasedOn: Vui lòng nhập ngày mất");
        assertThat(invalid(new MarkDeceasedRequest(day, false)))
                .containsExactly("confirmed: Cần xác nhận đánh dấu đã mất (không hoàn tác được)");
        assertThat(invalid(new MarkDeceasedRequest(day, null))).containsExactly("confirmed: Vui lòng xác nhận");
    }

    @Test
    void transferNeedsNewOwnerAndExplicitConfirmation() {
        assertThat(invalid(new TransferOwnerRequest(412L, true))).isEmpty();
        assertThat(invalid(new TransferOwnerRequest(null, true))).containsExactly("newCustomerId: Vui lòng chọn chủ mới");
        assertThat(invalid(new TransferOwnerRequest(412L, false)))
                .containsExactly("previousOwnerConfirmed: Cần xác nhận chủ cũ có mặt hoặc đã đồng ý");
        assertThat(invalid(new TransferOwnerRequest(412L, null))).hasSize(1);
    }
}
