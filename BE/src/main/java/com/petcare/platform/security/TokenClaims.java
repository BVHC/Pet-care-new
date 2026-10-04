package com.petcare.platform.security;

/**
 * Các claim định danh của access token đã qua kiểm tra chữ ký (docs/adr/0003): {@code sub} = {@code accounts.id},
 * {@code sid} = {@code sessions.id}, {@code jti} = bí mật gắn token với phiên ({@code sessions.token_hash}).
 * Role, chi nhánh không nằm trong token mà đọc lại từ DB ở mỗi request.
 */
public record TokenClaims(long accountId, long sessionId, String jti) {
}
