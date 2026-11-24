package com.petcare.module.catalog.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.petcare.module.catalog.api.ProductType;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Bean Validation của {@code galleryUrls} trên hai request sản phẩm (docs/adr/0151): tối đa 8 phần tử, mỗi phần tử
 * không rỗng và tối đa 500 ký tự. Kiểm chạy TRƯỚC trim và bỏ trùng, nên 9 phần tử trùng nhau còn 8 vẫn bị từ chối.
 * {@code null} (vắng) là hợp lệ: tạo mới thành {@code []}, sửa nghĩa là không đổi.
 */
class ProductGalleryValidationTest {

    private static final String TOO_MANY = "Tối đa 8 ảnh phụ";
    private static final String BLANK = "Đường dẫn ảnh phụ không được để trống";
    private static final String TOO_LONG = "Đường dẫn ảnh phụ tối đa 500 ký tự";

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

    private static CreateProductRequest create(List<String> gallery) {
        return new CreateProductRequest(1L, "SKU-1", "Sản phẩm", ProductType.GOODS, false, false, null, "hộp", 100L,
                null, null, gallery);
    }

    private static UpdateProductRequest update(List<String> gallery) {
        return new UpdateProductRequest(null, null, null, null, null, null, null, null, null, gallery, null);
    }

    private static List<String> urls(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "/imgs/" + i + ".jpg").toList();
    }

    private static List<String> withNull() {
        return Arrays.asList("/imgs/a.jpg", null);
    }

    /** Trả (đường dẫn thuộc tính, thông báo) của các vi phạm, để pin cả hai. */
    private static <T> List<String> violations(Set<ConstraintViolation<T>> found) {
        List<String> out = new ArrayList<>();
        for (ConstraintViolation<T> v : found) {
            out.add(v.getPropertyPath() + " | " + v.getMessage());
        }
        return out.stream().sorted().toList();
    }

    @Test
    void absentEmptyAndUpToEightValidUrlsPass() {
        for (List<String> ok : Arrays.<List<String>>asList(null, List.of(), urls(1), urls(8))) {
            assertThat(validator.validate(create(ok))).as("create %s", ok).isEmpty();
            assertThat(validator.validate(update(ok))).as("update %s", ok).isEmpty();
        }
    }

    @Test
    void relativePathsAndAnyProtocolAreNotCheckedLikeImageUrl() {
        List<String> odd = List.of("/products/x.jpg", "http://cdn.example/x.png", "ftp://h/x", "chữ lạ.jpg");

        assertThat(validator.validate(create(odd))).isEmpty();
        assertThat(validator.validate(update(odd))).isEmpty();
    }

    @Test
    void nineElementsAreRejectedOnBothRequests() {
        assertThat(violations(validator.validate(create(urls(9))))).containsExactly("galleryUrls | " + TOO_MANY);
        assertThat(violations(validator.validate(update(urls(9))))).containsExactly("galleryUrls | " + TOO_MANY);
    }

    @Test
    void nineIdenticalElementsAreStillRejectedBecauseValidationRunsBeforeDeduplication() {
        List<String> same = IntStream.range(0, 9).mapToObj(i -> "/imgs/a.jpg").toList();

        assertThat(violations(validator.validate(create(same)))).containsExactly("galleryUrls | " + TOO_MANY);
        assertThat(violations(validator.validate(update(same)))).containsExactly("galleryUrls | " + TOO_MANY);
    }

    @Test
    void emptyAndWhitespaceOnlyElementsAreRejectedWithTheirIndex() {
        assertThat(violations(validator.validate(create(List.of("/imgs/a.jpg", "")))))
                .containsExactly("galleryUrls[1].<list element> | " + BLANK);
        assertThat(violations(validator.validate(update(List.of("   ", "/imgs/a.jpg")))))
                .containsExactly("galleryUrls[0].<list element> | " + BLANK);
    }

    @Test
    void aNullElementIsRejectedAsBlank() {
        assertThat(violations(validator.validate(create(withNull()))))
                .containsExactly("galleryUrls[1].<list element> | " + BLANK);
        assertThat(violations(validator.validate(update(withNull()))))
                .containsExactly("galleryUrls[1].<list element> | " + BLANK);
    }

    @Test
    void anElementOfExactly500CharsPassesAndOneMoreCharFails() {
        assertThat(validator.validate(create(List.of("a".repeat(500))))).isEmpty();
        assertThat(validator.validate(update(List.of("a".repeat(500))))).isEmpty();

        assertThat(violations(validator.validate(create(List.of("a".repeat(501))))))
                .containsExactly("galleryUrls[0].<list element> | " + TOO_LONG);
        assertThat(violations(validator.validate(update(List.of("/imgs/a.jpg", "a".repeat(501))))))
                .containsExactly("galleryUrls[1].<list element> | " + TOO_LONG);
    }
}
