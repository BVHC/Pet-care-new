package com.petcare.module.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** BR-AI-03: từ khóa cấp cứu từ tệp tài nguyên {@code ai/ai-emergency-keywords.txt}; ca âm tính "liệt kê" (07 §14.1). */
class EmergencyDetectorTest {

    private final EmergencyDetector detector = new EmergencyDetector();

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "Chó ăn nhầm thuốc chuột",
            "Mèo co giật liên tục",
            "Chó khó thở thở dốc",
            "Chó bị ngộ độc",
            "Mèo ăn nhầm chocolate",
            "Chó ăn nhầm hóa chất tẩy rửa",
            "Vết thương chảy máu không cầm được",
            "Chó bất tỉnh",
            "Mèo ngất xỉu",
            "Chó bị liệt chân sau",
            "Chó bị liệt nửa người",
            "Mèo bụng căng cứng",
            "Chó bị xe đâm",
            "Mèo rơi từ trên cao xuống",
            "Mèo không đi tiểu hai ngày",
            "chó kho tho",
    })
    void emergencyKeywordsAreRecognised(String question) {
        assertThat(detector.isEmergency(question)).as(question).isTrue();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "Liệt kê giúp tôi các mũi tiêm cho mèo con",
            "Chó con tiêm phòng dại lúc mấy tuần?",
            "Chó ăn chocolate có sao không?",
            "Mèo con ăn mấy bữa một ngày?",
            "Chó ngat",
    })
    void ordinaryQuestionsAreNot(String question) {
        assertThat(detector.isEmergency(question)).as(question).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"Liệt kê giúp tôi,false", "Chó liệt chân,true"})
    void theBareWordLietIsNeverAKeyword(String question, boolean expected) {
        assertThat(detector.isEmergency(question)).isEqualTo(expected);
    }
}
