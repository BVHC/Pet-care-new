package com.petcare.module.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Nhận ý định {@code BUSINESS_PRICE} / {@code BUSINESS_BRANCH} bằng từ khóa (BR-AI-31, cùng cơ chế BR-AI-26;
 * docs/adr/0134 "Đã cài ở T90"). Chỉ kiểm câu hỏi có ý định hay không: việc trả lời nằm ở {@code BusinessAnswerServiceTest}.
 */
class BusinessIntentsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Giá tắm cho mèo bao nhiêu?",
            "Giá tiêm vaccine dại bao nhiêu?",
            "chi phí khám tổng quát là bao nhiêu",
            "Một lần tắm bao nhiêu tiền vậy?",
            "bao nhieu tien mot lan cat tia long",
            "Cho mình xem bảng giá dịch vụ",
            "GIÁ chuồng chó lớn"})
    void priceQuestionsAreRecognised(String question) {
        assertThat(BusinessIntents.isPrice(question)).as(question).isTrue();
    }

    /** "giá" chỉ đứng sau ranh giới từ vẫn trùng "giá trị": cụm loại trừ trong tệp từ khóa mới chặn được. */
    @ParameterizedTest
    @ValueSource(strings = {
            "Giá trị dinh dưỡng của hạt cho mèo con là gì?",
            "Thức ăn nào có giá trị dinh dưỡng cao?",
            "chải lông bao lâu một lần",
            "Mèo bị già có cần ăn khác không?",
            "Chó mèo nên tiêm vaccine lúc mấy tuần tuổi?"})
    void careQuestionsAreNotPriceQuestions(String question) {
        assertThat(BusinessIntents.isPrice(question)).as(question).isFalse();
        assertThat(BusinessIntents.mentionsWeakPrice(question)).as(question).isFalse();
    }

    @Test
    void aValueOfNutritionDoesNotHideARealPriceWordInTheSameSentence() {
        assertThat(BusinessIntents.isPrice("Giá trị dinh dưỡng thế nào và giá tắm bao nhiêu?")).isTrue();
    }

    /** "phí" một mình quá rộng ("lãng phí"): chỉ là tín hiệu yếu, chỉ có nghĩa khi câu còn nhắc tên một dịch vụ. */
    @Test
    void aBareFeeWordIsOnlyAWeakSignal() {
        assertThat(BusinessIntents.isPrice("phí tắm mèo")).isFalse();
        assertThat(BusinessIntents.mentionsWeakPrice("phí tắm mèo")).isTrue();
        assertThat(BusinessIntents.isPrice("chi phí tắm mèo")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Chi nhánh Pet Care ở đâu?",
            "cho mình xin địa chỉ phòng khám",
            "Giờ mở cửa của phòng khám là mấy giờ?",
            "số điện thoại của bên bạn là gì",
            "có hotline không",
            "Chi nhánh nào nhận cấp cứu ngoài giờ?"})
    void branchQuestionsAreRecognised(String question) {
        assertThat(BusinessIntents.isBranch(question)).as(question).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Mèo bị nôn nên làm gì?",
            "Bao lâu thì nên tắm cho chó một lần?",
            "Lịch hẹn sắp tới của tôi là khi nào?"})
    void otherQuestionsHaveNoBusinessIntent(String question) {
        assertThat(BusinessIntents.isBranch(question)).as(question).isFalse();
        assertThat(BusinessIntents.isPrice(question)).as(question).isFalse();
    }
}
