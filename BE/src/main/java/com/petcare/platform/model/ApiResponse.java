package com.petcare.platform.model;

public record ApiResponse<T>(
    T data,
    String message,
    int code
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, "success", 200);
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(data, message, 200);
    }

    public static <T> ApiResponse<T> created(T data, String message) {
        return new ApiResponse<>(data, message, 201);
    }

    public static <T> ApiResponse<T> error(String message, int code) {
        return new ApiResponse<>(null, message, code);
    }
}
