package com.petcare.module.auth.dto;

/**
 * docs/api/auth-v1.md #LogoutResponse — {@code revoked} phản ánh đúng việc có refresh token
 * nào thực sự bị thu hồi hay không (RULE-01-06). Access token luôn bị blacklist bất kể giá trị
 * này (xem TokenIssuanceFacadeImpl#logout) — {@code false} chỉ nghĩa là không tìm thấy phiên
 * refresh token nào tương ứng để thu hồi (vd đã hết hạn/đã revoke từ trước).
 */
public record LogoutResponse(boolean revoked) {
}
