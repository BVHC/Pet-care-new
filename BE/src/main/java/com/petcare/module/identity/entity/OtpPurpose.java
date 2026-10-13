package com.petcare.module.identity.entity;

/** {@code otp_tokens.purpose} (erd §1, BR-TK-04). */
public enum OtpPurpose {
    REGISTER,
    RESET_PASSWORD,
    CHANGE_EMAIL,
    LINK_PROFILE;

    /**
     * Mã mới hủy mã cũ cùng mục đích <b>của chính tài khoản đó</b> thay vì theo email nhận (BR-TK-05): mỗi tài khoản tối
     * đa một mã còn sống, tài khoản khác nhắm cùng email không hủy được mã này, và mọi lệnh ghi lên mã của một tài khoản
     * nằm dưới khóa dòng {@code accounts} của nó (docs/adr/0011). {@code LINK_PROFILE} (docs/adr/0025 mục 4),
     * {@code CHANGE_EMAIL} (docs/adr/0034). {@code REGISTER}, {@code RESET_PASSWORD} hủy theo {@code (email, purpose)}.
     */
    public boolean invalidatesPerAccount() {
        return this == LINK_PROFILE || this == CHANGE_EMAIL;
    }
}
