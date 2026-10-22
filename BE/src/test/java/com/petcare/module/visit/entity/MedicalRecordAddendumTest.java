package com.petcare.module.visit.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import jakarta.persistence.Column;

/** {@link MedicalRecordAddendum} (docs/adr/0064): mỗi tham số vào đúng cột; bảng LOG chỉ thêm mới (G9). */
class MedicalRecordAddendumTest {

    @Test
    void ofKeepsEachColumn() {
        MedicalRecordAddendum addendum = MedicalRecordAddendum.of(9001L, "Bổ sung kết quả máu", true, 45L);

        assertThat(addendum.getMedicalRecordId()).isEqualTo(9001L);
        assertThat(addendum.getContent()).isEqualTo("Bổ sung kết quả máu");
        assertThat(addendum.isInternal()).isTrue();
        assertThat(addendum.getCreatedBy()).isEqualTo(45L);
        assertThat(addendum.getId()).as("id do DB sinh").isNull();
    }

    @Test
    void publicAddendumIsNotInternal() {
        assertThat(MedicalRecordAddendum.of(9001L, "x", false, 45L).isInternal()).isFalse();
    }

    /** G9: bỏ {@code updatable = false} ở bất kỳ cột nào → đỏ. */
    @Test
    void everyColumnIsInsertOnly() {
        Field[] columns = Arrays.stream(MedicalRecordAddendum.class.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(Column.class)).toArray(Field[]::new);

        assertThat(columns).extracting(Field::getName)
                .containsExactlyInAnyOrder("medicalRecordId", "content", "internal", "createdBy");
        assertThat(columns).allSatisfy(f -> assertThat(f.getAnnotation(Column.class).updatable())
                .as(f.getName()).isFalse());
    }
}
