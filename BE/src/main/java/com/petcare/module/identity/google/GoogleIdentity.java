package com.petcare.module.identity.google;

/**
 * Danh tính đã kiểm từ ID token Google (docs/adr/0152).
 *
 * @param sub                định danh ổn định của người dùng Google; dùng để tìm tài khoản, không dùng email
 * @param email              đã {@code strip().toLowerCase(Locale.ROOT)}
 * @param name               claim {@code name}, hoặc {@code given_name + " " + family_name}; {@code null} khi không có cả ba
 * @param emailAuthoritative Google là bên cấp email này: kết thúc bằng {@code @gmail.com} hoặc token có claim {@code hd}
 */
public record GoogleIdentity(String sub, String email, String name, boolean emailAuthoritative) {
}
