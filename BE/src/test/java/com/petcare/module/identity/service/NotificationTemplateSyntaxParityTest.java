package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.petcare.module.care.service.NotificationTemplateRenderer;
import com.petcare.module.identity.api.NotificationTemplateQueryApi.TemplateView;

/**
 * Bộ quét biến lúc lưu mẫu ({@link TemplateVariables}, identity) và renderer lúc gửi
 * ({@link NotificationTemplateRenderer}, care — giữ nguyên code) phải cùng một ngữ pháp (docs/adr/0037 mục 4). Hai lớp ở
 * hai module nên không dùng chung code; test này (chỉ ở test, main không import chéo) là thứ giữ chúng khớp nhau, khuôn
 * {@code StaffPresenceParityTest}.
 *
 * <p>Với payload gán mỗi biến cho phép một giá trị canh {@code §i§} (không chứa ngoặc):
 * <ul>
 *   <li>bộ quét chấp nhận ⇔ nội dung render không còn {@code {} hay {@code }} nào;</li>
 *   <li>biến {@code a} nằm trong {@code used} ⇔ nội dung render chứa giá trị canh của {@code a}.</li>
 * </ul>
 * Hai vế này là điều BR-QT-14 cần: mẫu đã lưu không bao giờ làm lộ ngoặc / biến lạ khi gửi, và kiểm "đủ biến bắt buộc"
 * dựa đúng vào những biến renderer sẽ thay.
 */
class NotificationTemplateSyntaxParityTest {

    private static final List<String> ALLOWED = List.of("ma_otp", "thoi_han_phut", "ten_khach", "thoi_diem",
            "thoi_diem_mo_khoa");
    private static final List<String> FRAGMENTS = List.of("{", "}", "{{", "}}", "{}", "{ma_otp}", "{thoi_han_phut}",
            "{ten_khach}", "{thoi_diem}", "{thoi_diem_mo_khoa}", "{abc}", "{ ma_otp}", "{MA_OTP}", "{ma otp}",
            "ma_otp", "{ma_", "otp}", "_mo_khoa}", "{thoi_diem", "x", "Mã ", "\n", "\r\n", "$1", "\\", "🐶", " ");

    private final NotificationTemplateRenderer renderer = new NotificationTemplateRenderer();

    @Test
    void scannerAndRendererAgreeOnGeneratedTexts() {
        Random random = new Random(20261013L);
        for (int n = 0; n < 20_000; n++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(13);
            for (int i = 0; i < length; i++) {
                text.append(FRAGMENTS.get(random.nextInt(FRAGMENTS.size())));
            }
            assertAgree(text.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Không có biến", "{ma_otp}", "{{ma_otp}}", "{ma_otp", "ma_otp}", "{}", "{ ma_otp }",
            "{MA_OTP}", "{ma_{ma_otp}", "{thoi_diem}{thoi_diem_mo_khoa}", "{thoi_diem_mo_khoa", "}{ma_otp}{",
            "Chào {ten_khach},\n\nMã: {ma_otp} ({thoi_han_phut} phút)"})
    void scannerAndRendererAgreeOnEdgeCases(String text) {
        assertAgree(text);
    }

    @Test
    void everySeededTemplateShapeIsAcceptedByBoth() {
        String otpRegister = "Chào {ten_khach},\n\nMã xác thực đăng ký tài khoản Pet Care của bạn là: {ma_otp}\n\n"
                + "Mã có hiệu lực trong {thoi_han_phut} phút và chỉ dùng được một lần.";

        assertThat(TemplateVariables.scan(otpRegister, ALLOWED).valid()).isTrue();
        assertAgree(otpRegister);
    }

    private void assertAgree(String text) {
        Map<String, Object> payload = new HashMap<>();
        for (int i = 0; i < ALLOWED.size(); i++) {
            payload.put(ALLOWED.get(i), sentinel(i));
        }
        TemplateView template = new TemplateView("IT_PARITY", "EMAIL", null, text, ALLOWED, List.of());
        String rendered = renderer.render(template, payload).body();
        TemplateVariables.Scan scan = TemplateVariables.scan(text, ALLOWED);

        boolean bracesLeft = rendered.indexOf('{') >= 0 || rendered.indexOf('}') >= 0;
        assertThat(scan.valid()).as("hợp lệ ⇔ render không còn ngoặc — %s → %s", text, rendered)
                .isEqualTo(!bracesLeft);
        for (int i = 0; i < ALLOWED.size(); i++) {
            assertThat(scan.used().contains(ALLOWED.get(i)))
                    .as("biến %s được quét ⇔ được thay — %s → %s", ALLOWED.get(i), text, rendered)
                    .isEqualTo(rendered.contains(sentinel(i)));
        }
    }

    private static String sentinel(int index) {
        return "§" + index + "§";
    }
}
