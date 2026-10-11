package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Dạng che email nhận mã liên kết (docs/adr/0027): không bao giờ lộ phần tên quá 2 ký tự, giữ domain. */
class EmailMaskerTest {

    @ParameterizedTest
    @CsvSource({
            "nguyen.van.a@gmail.com, ng***@gmail.com",
            "abc@x.vn, ab***@x.vn",
            "ab@x.vn, a***@x.vn",
            "a@x.vn, a***@x.vn",
            "NG@X.VN, N***@X.VN",
            "ngọc.anh@petcare.test, ng***@petcare.test",
            "khong-co-a-cong, ***",
            "@x.vn, ***",
            "ten@, ***"
    })
    void masksLocalPartAndKeepsDomain(String email, String expected) {
        assertThat(EmailMasker.mask(email)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"nguyen.van.a@gmail.com", "abc@x.vn"})
    void neverRevealsTheFullLocalPart(String email) {
        String local = email.substring(0, email.indexOf('@'));
        assertThat(EmailMasker.mask(email)).doesNotContain(local);
    }

    @org.junit.jupiter.api.Test
    void nullStaysNull() {
        assertThat(EmailMasker.mask(null)).isNull();
    }
}
