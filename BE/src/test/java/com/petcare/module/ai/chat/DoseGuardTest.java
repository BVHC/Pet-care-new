package com.petcare.module.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Hậu kiểm liều thuốc theo ngữ cảnh (BR-AI-06, 07 §14.3). Các câu lấy từ bộ tổng hợp của spike T56
 * ({@code BE/scripts/spike/dose-cases.mjs}); ca `edge` (heuristic cố ý bắt nhầm hay bỏ sót) không nằm ở đây.
 */
class DoseGuardTest {

    private final DoseGuard guard = new DoseGuard();

    @ParameterizedTest(name = "[{index}] chặn: {0}")
    @ValueSource(strings = {
            "Mỗi ngày cho uống 1 viên.",
            "1 viên mỗi ngày",
            "Cho chó uống 5 mg mỗi ngày.",
            "Paracetamol 10 mg/kg mỗi 8 giờ.",
            "Tiêm 500 IU vào cơ.",
            "Mỗi lần dùng 1 ống.",
            "Dùng 2 ml thuốc tẩy giun cho mỗi 5 kg.",
            "Tiêm 0,5 ml vaccine cho chó.",
            "Nhỏ 3 giọt thuốc nhỏ mắt vào mỗi bên.",
            "Cho mèo uống 2–3 ml siro mỗi ngày.",
            "Liều amoxicillin là 12 mg cho mỗi kg.",
            "Câu đầu bình thường. Sau đó cho uống 250 mcg ivermectin.",
    })
    void doseMentionsAreBlocked(String answer) {
        assertThat(guard.violates(answer)).as(answer).isTrue();
    }

    @ParameterizedTest(name = "[{index}] không chặn: {0}")
    @ValueSource(strings = {
            "500 ml nước",
            "Chó 10 kg cần khoảng 500–600 ml nước mỗi ngày.",
            "Nhỏ 3–4 giọt dung dịch vệ sinh tai vào tai chó.",
            "Nhỏ 2 giọt dung dịch vệ sinh mắt lên gạc sạch.",
            "Chia thành 3 bữa nhỏ mỗi ngày.",
            "Tiêm mũi đầu từ 8 tuần tuổi, nhắc lại sau 21 ngày.",
            "Cho ăn khoảng 200 g thức ăn hạt mỗi ngày.",
            "Mỗi ngày cho uống nước sạch, khoảng 60 ml cho mỗi kg thể trọng.",
            "Cắt móng mỗi 2–4 tuần.",
            "Đánh răng ít nhất 3 lần mỗi tuần.",
            "Pha 5 ml nước ấm với một ít nước dùng không muối để kích thích ăn.",
    })
    void waterFoodAndEarCleanerQuantitiesAreNotBlocked(String answer) {
        assertThat(guard.violates(answer)).as(answer).isFalse();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "Không nên tự ý cho uống paracetamol vì độc với chó. Hãy đặt lịch khám.",
            "",
    })
    void textWithoutANumberedDoseIsNotBlocked(String answer) {
        assertThat(guard.violates(answer)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void anActiveIngredientFromTheResourceFileMakesAMlQuantityADose() {
        assertThat(guard.violates("Cho uống 3 ml paracetamol dạng nước.")).isTrue();
        assertThat(guard.violates("Cho uống 3 ml nước ấm.")).isFalse();
    }
}
