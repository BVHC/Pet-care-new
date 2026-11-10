package com.petcare.module.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** BR-AI-12a: email, số điện thoại (`0…`, `+84…`), dãy 9 hoặc 12 chữ số → `[email]`, `[sđt]`, `[số]`. */
class PiiMaskerTest {

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "Gửi kết quả về hien.nguyen+pet@gmail.com nhé|Gửi kết quả về [email] nhé",
            "Gọi 0901234567 giúp mình|Gọi [sđt] giúp mình",
            "Số của mình là 0901 234 567|Số của mình là [sđt]",
            "Zalo +84 901 234 567 nhé|Zalo [sđt] nhé",
            "Số 090.123.4567 đây|Số [sđt] đây",
            "CMND 123456789 của tôi|CMND [số] của tôi",
            "CCCD 012345678901 của tôi|CCCD [số] của tôi",
            "Chó 10 kg uống bao nhiêu ml nước|Chó 10 kg uống bao nhiêu ml nước",
            "Mochi 12 tuổi tiêm lúc 8 tuần|Mochi 12 tuổi tiêm lúc 8 tuần",
    })
    void masksPersonalData(String raw, String expected) {
        assertThat(PiiMasker.mask(raw)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "a@b.co và 0912345678 và 123456789|[email] và [sđt] và [số]",
    })
    void masksEveryKindInOneText(String raw, String expected) {
        assertThat(PiiMasker.mask(raw)).isEqualTo(expected);
    }
}
