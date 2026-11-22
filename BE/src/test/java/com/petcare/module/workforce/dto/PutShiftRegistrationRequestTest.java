package com.petcare.module.workforce.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BindingResult;
import org.springframework.validation.DataBinder;
import org.springframework.validation.FieldError;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import jakarta.validation.ConstraintViolation;

/**
 * Ràng buộc hình thức của {@link PutShiftRegistrationRequest} / {@link ShiftRegistrationItem} (workforce-v1 #15, ADR-0137
 * mục 12). Hai tầng: Hibernate Validator thật, và <b>đúng đường của Spring MVC</b> cho {@code @Valid @RequestBody}
 * ({@link DataBinder#validate()} trên một record, rồi {@code BindingResult} của {@code MethodArgumentNotValidException}).
 * Tầng thứ hai là bằng chứng của bước 0 trong kế hoạch N3-S7: lỗi ở đường dẫn có chỉ số ({@code items[0].workDate}) phải
 * tạo được {@link FieldError} chứ không ném {@code NotReadablePropertyException} (sẽ thành 500).
 */
class PutShiftRegistrationRequestTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);

    private static LocalValidatorFactoryBean validator;

    @BeforeAll
    static void start() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
    }

    @AfterAll
    static void stop() {
        validator.close();
    }

    @Test
    void aValidRequestHasNoViolationAndAnEmptyListIsValidInShape() {
        assertThat(validator.validate(new PutShiftRegistrationRequest(List.of(new ShiftRegistrationItem(DAY, 1L)))))
                .isEmpty();
        assertThat(validator.validate(new PutShiftRegistrationRequest(List.of()))).isEmpty();
    }

    @Test
    void missingItemsIsRejectedWithTheVietnameseMessage() {
        assertThat(messages(new PutShiftRegistrationRequest(null)))
                .containsExactly("items: Vui lòng gửi danh sách ca đăng ký");
    }

    @Test
    void oneHundredItemsIsAcceptedAndOneHundredAndOneIsRejected() {
        assertThat(validator.validate(new PutShiftRegistrationRequest(items(100)))).isEmpty();
        assertThat(messages(new PutShiftRegistrationRequest(items(101))))
                .containsExactly("items: Tối đa 100 mục mỗi lần đăng ký");
    }

    @Test
    void aNullElementAndNullFieldsAreRejectedOnTheirIndexedPath() {
        List<ShiftRegistrationItem> list = new ArrayList<>();
        list.add(new ShiftRegistrationItem(DAY, 1L));
        list.add(null);
        list.add(new ShiftRegistrationItem(null, 2L));
        list.add(new ShiftRegistrationItem(DAY, null));

        assertThat(messages(new PutShiftRegistrationRequest(list))).containsExactlyInAnyOrder(
                "items[1].<list element>: Mục đăng ký không được để trống",
                "items[2].workDate: Vui lòng chọn ngày làm",
                "items[3].shiftDefinitionId: Vui lòng chọn ca làm việc");
    }

    /** Bước 0: đường của Spring MVC trên record không ném lỗi khi dựng {@link FieldError} cho đường dẫn có chỉ số. */
    @Test
    void theSpringMvcBinderBuildsFieldErrorsForIndexedPathsOnARecordWithoutThrowing() {
        List<ShiftRegistrationItem> list = new ArrayList<>(Arrays.asList(null, new ShiftRegistrationItem(null, null)));
        List<ShiftRegistrationItem> tooMany = items(101);

        for (PutShiftRegistrationRequest request : List.of(new PutShiftRegistrationRequest(list),
                new PutShiftRegistrationRequest(tooMany), new PutShiftRegistrationRequest(null))) {
            DataBinder binder = new DataBinder(request, "putShiftRegistrationRequest");
            binder.setValidator(validator);

            binder.validate();

            BindingResult result = binder.getBindingResult();
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.getAllErrors()).allSatisfy(error -> assertThat(error).isInstanceOf(FieldError.class));
        }
    }

    /** Tên trường mà {@code GlobalExceptionHandler.describe} đưa vào message 400 (client chỉ cần biết mục thứ mấy). */
    @Test
    void theFieldNamesTheClientSeesAreTheIndexedPaths() {
        List<ShiftRegistrationItem> list = new ArrayList<>(Arrays.asList(null, new ShiftRegistrationItem(null, 1L)));
        DataBinder binder = new DataBinder(new PutShiftRegistrationRequest(list), "putShiftRegistrationRequest");
        binder.setValidator(validator);

        binder.validate();

        assertThat(binder.getBindingResult().getFieldErrors().stream().map(FieldError::getField).sorted().toList())
                .containsExactly("items[0]", "items[1].workDate");
    }

    private static List<String> messages(PutShiftRegistrationRequest request) {
        Set<ConstraintViolation<PutShiftRegistrationRequest>> violations = validator.validate(request);
        return violations.stream().map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted()
                .collect(Collectors.toList());
    }

    private static List<ShiftRegistrationItem> items(int count) {
        return new ArrayList<>(Collections.nCopies(count, new ShiftRegistrationItem(DAY, 1L)));
    }
}
