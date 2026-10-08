package com.petcare.platform.model;

/**
 * Envelope cho mọi response thành công: payload nằm một cấp trong {@code data};
 * danh sách thì {@code data} là {@link PageResponse}. {@code code} là HTTP status.
 */
public record ApiResponse<T>(T data, String message, int code) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, "success", 200);
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(data, message, 200);
    }

    public static <T> ApiResponse<T> created(T data, String message) {
        return new ApiResponse<>(data, message, 201);
    }

    /** HTTP 202: yêu cầu đã nhận nhưng không tiết lộ kết quả, ví dụ quên mật khẩu (docs/api/00-method.md §3.4). */
    public static <T> ApiResponse<T> accepted(T data, String message) {
        return new ApiResponse<>(data, message, 202);
    }
}
