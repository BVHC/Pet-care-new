package com.petcare.platform.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Một mã lỗi mà endpoint thật sự trả, đưa vào tài liệu OpenAPI (docs/adr/0040 mục 2). Dùng thay cho
 * {@code @io.swagger.v3.oas.annotations.responses.ApiResponse}, vì tên đó trùng {@code platform.model.ApiResponse} mà
 * mọi controller đã import.
 * <p>
 * {@link #description()} rỗng: tham chiếu response dùng chung cùng mã ({@code BadRequest}, {@code Unauthorized},
 * {@code Forbidden}, {@code NotFound} — {@link OpenApiConfig}). Có mô tả: response riêng với schema
 * {@code ErrorResponse}. Text chép nguyên văn từ hợp đồng {@code docs/api/openapi/*.yaml}; {@code 401} của endpoint cần
 * đăng nhập và {@code 500} được thêm tự động, không cần khai báo.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ApiErrorDocs.class)
public @interface ApiErrorDoc {

    /** HTTP status, ví dụ 400, 404, 409. */
    int status();

    /** Mô tả riêng của endpoint; rỗng = dùng response chung của mã {@link #status()}. */
    String description() default "";
}
