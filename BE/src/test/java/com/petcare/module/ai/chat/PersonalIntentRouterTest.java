package com.petcare.module.ai.chat;

import static com.petcare.module.ai.chat.PersonalIntent.APPOINTMENT_UPCOMING;
import static com.petcare.module.ai.chat.PersonalIntent.VACCINATION_SCHEDULE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Định tuyến ý định cá nhân (BR-AI-26, 07 §14.3) và nhận câu hỏi bệnh án (BR-AI-28b). Bảng ca lấy từ bộ 43 câu §14.1:
 * dấu hiệu mạnh và yếu, tên thú trùng từ thông dụng, ranh giới từ. Danh sách luật nằm ở tệp tài nguyên
 * {@code ai/ai-personal-intent.txt}.
 */
class PersonalIntentRouterTest {

    private static final List<String> MULTI = List.of("Mochi", "Kitty");
    private static final List<String> VANG = List.of("Vàng");

    private final PersonalIntentRouter router = new PersonalIntentRouter();

    private Optional<PersonalIntent> detect(String question, List<String> pets) {
        return router.detect(question, pets);
    }

    @Test
    void appointmentAcceptsAWeakSign() {
        assertThat(detect("Sắp tới tôi có lịch hẹn không?", List.of())).contains(APPOINTMENT_UPCOMING);
        assertThat(detect("Hôm nay có lịch khám không?", List.of())).contains(APPOINTMENT_UPCOMING);
    }

    @Test
    void appointmentAcceptsAStrongSign() {
        assertThat(detect("Lịch hẹn của tôi", List.of())).contains(APPOINTMENT_UPCOMING);
        assertThat(detect("Mochi có lịch hẹn nào không", MULTI)).contains(APPOINTMENT_UPCOMING);
    }

    @Test
    void appointmentKeywordWinsOverVaccination() {
        assertThat(detect("Lịch hẹn tiêm của tôi", List.of())).contains(APPOINTMENT_UPCOMING);
    }

    @Test
    void vaccinationNeedsAStrongSign() {
        assertThat(detect("Lịch tiêm của bé nhà tôi", MULTI)).contains(VACCINATION_SCHEDULE);
        assertThat(detect("Mochi tiêm khi nào?", MULTI)).contains(VACCINATION_SCHEDULE);
        assertThat(detect("Mũi tiêm tiếp theo của tôi là khi nào?", List.of())).contains(VACCINATION_SCHEDULE);
    }

    @Test
    void anOldPetIsNotAPriceQuestion() {
        // "bé già nhà tôi": "già" không trùng cụm chỉ giá, "nhà tôi" là dấu hiệu mạnh
        assertThat(detect("Lịch tiêm sắp tới của bé già nhà tôi", MULTI)).contains(VACCINATION_SCHEDULE);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "Chó con tiêm phòng dại lúc mấy tuần?",
            "Giá tiêm vaccine dại?",
            "Mũi tiêm tiếp theo của chó con là khi nào?",
            "Quá hạn tiêm vaccine dại có sao không?",
            "Liệt kê giúp tôi các mũi tiêm cho mèo con",
            "Giá vàng hôm nay là bao nhiêu?",
    })
    void generalQuestionsAreNotPersonal(String question) {
        assertThat(detect(question, MULTI)).as(question).isEmpty();
        assertThat(detect(question, List.of())).as(question).isEmpty();
    }

    @Test
    void aPriceQuestionIsNeverPersonalEvenWithAStrongSign() {
        assertThat(detect("Giá tiêm vaccine cho bé nhà tôi bao nhiêu tiền?", MULTI)).isEmpty();
        assertThat(detect("Chi phí tiêm của bé nhà mình", MULTI)).isEmpty();
    }

    @Test
    void shopWordTiemIsNotTheWordTiem() {
        assertThat(detect("Tiệm thú y của tôi ở đâu?", List.of())).isEmpty();
    }

    @Test
    void aCommonWordPetNameCountsOnlyAfterAPrefix() {
        assertThat(detect("Con Vàng tiêm khi nào?", VANG)).contains(VACCINATION_SCHEDULE);
        assertThat(detect("Bé vàng tiêm khi nào?", VANG)).contains(VACCINATION_SCHEDULE);
        assertThat(detect("Vàng tiêm khi nào?", VANG)).isEmpty();
        assertThat(detect("Giá vàng hôm nay là bao nhiêu?", VANG)).isEmpty();
    }

    @Test
    void anOrdinaryPetNameIsAStrongSignOnItsOwn() {
        assertThat(router.mentionedPetNames("Mochi tiêm khi nào?", MULTI)).containsExactly("Mochi");
        assertThat(router.mentionedPetNames("Mochi và Kitty", MULTI)).containsExactly("Mochi", "Kitty");
        assertThat(router.mentionedPetNames("Con Vàng ngoan", VANG)).containsExactly("Vàng");
        assertThat(router.mentionedPetNames("Giá vàng", VANG)).isEmpty();
    }

    @Test
    void aGuestHasNoPetNamesSoOnlyPhraseSignsCount() {
        assertThat(detect("Mochi tiêm khi nào?", List.of())).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "Đơn thuốc của tôi hôm qua là gì?",
            "Toa thuốc của bé nhà mình",
            "Bệnh án của nhà tôi",
            "Kết quả xét nghiệm của con nhà tôi thế nào?",
    })
    void recordQuestionsWithAStrongSignAreRecognisedWithoutPetNames(String question) {
        assertThat(router.isMedicalRecordQuestion(question, List.of())).as(question).isTrue();
    }

    @Test
    void recordQuestionsNeedAStrongSignOrAPetName() {
        assertThat(router.isMedicalRecordQuestion("Đơn thuốc của Mochi hôm qua là gì?", MULTI)).isTrue();
        assertThat(router.isMedicalRecordQuestion("Đơn thuốc của Mochi hôm qua là gì?", List.of())).isFalse();
        assertThat(router.isMedicalRecordQuestion("Đơn thuốc là gì?", MULTI)).isFalse();
        assertThat(router.isMedicalRecordQuestion("Mochi tiêm khi nào?", MULTI)).isFalse();
    }
}
