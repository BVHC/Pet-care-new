package com.petcare.module.identity.google;

/** Kiểm một ID token Google (docs/adr/0152). Gọi mạng (JWKS), nên phải chạy ngoài transaction. */
public interface GoogleIdentityVerifier {

    /**
     * @throws com.petcare.module.identity.exception.InvalidCredentialsException      token không hợp lệ (401)
     * @throws com.petcare.module.identity.exception.GoogleSignInUnavailableException chưa cấu hình client ID hoặc không
     *                                                                                 lấy được JWKS (503)
     */
    GoogleIdentity verify(String credential);
}
