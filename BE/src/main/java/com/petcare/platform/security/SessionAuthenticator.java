package com.petcare.platform.security;

import java.util.Optional;

/**
 * Kiểm tra phiên của một token đã đúng chữ ký: phiên tồn tại, khớp {@code jti}, chưa bị hủy, chưa hết hạn, tài khoản
 * còn được phép truy cập (docs/adr/0003). Module TK implement; trả {@code empty} khi không hợp lệ, filter coi request
 * là ẩn danh. Lỗi hạ tầng (DB) thì ném exception.
 */
public interface SessionAuthenticator {

    Optional<SecurityPrincipal> authenticate(TokenClaims claims);
}
