package com.petcare.module.auth.dto;

/**
 * docs/api/auth-v1.md #LoginResponse — bỏ field `user` (userId/accountId/role
 * đã có sẵn trong claim của accessToken, trả thêm ở JSON body là thừa và lộ
 * thông tin không cần thiết; quyết định 2026-09-14, khác PROPOSED shape gốc
 * trong docs — cần đồng bộ lại docs/api/auth-v1.md + openapi/auth-v1.yaml).
 * `expiresIn` (giây) lấy từ IssuedTokenPair, hữu ích cho FE tự lên lịch refresh.
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn
) {
}
