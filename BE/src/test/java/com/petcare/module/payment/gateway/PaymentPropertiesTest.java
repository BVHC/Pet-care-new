package com.petcare.module.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link PaymentProperties} không bao giờ in khóa (task 127, BR-TG-07). */
class PaymentPropertiesTest {

    @Test
    void toStringMasksTheSecret() {
        String text = new PaymentProperties("VNPAY", "TESTCODE", "SUPER-SECRET-VALUE", "https://pay", "http://ret", null,
                null).toString();

        assertThat(text).doesNotContain("SUPER-SECRET-VALUE").contains("secret=<masked>").contains("TESTCODE");
    }

    @Test
    void toStringShowsUnsetForABlankSecret() {
        assertThat(new PaymentProperties("FAKE", null, "", null, null, null, null).toString())
                .contains("secret=<unset>");
        assertThat(new PaymentProperties("FAKE", null, null, null, null, null, null).toString())
                .contains("secret=<unset>");
    }
}
