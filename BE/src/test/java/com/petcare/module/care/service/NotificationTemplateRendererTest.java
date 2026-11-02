package com.petcare.module.care.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.petcare.module.care.service.NotificationTemplateRenderer.Rendered;
import com.petcare.module.identity.api.NotificationTemplateQueryApi.TemplateView;

/** Thay biến của mẫu (BR-QT-14, docs/adr/0012). */
class NotificationTemplateRendererTest {

    private final NotificationTemplateRenderer renderer = new NotificationTemplateRenderer();

    private static final TemplateView OTP = new TemplateView("OTP_REGISTER", "EMAIL", "Mã {ma_otp}",
            "Chào {ten_khach},\nMã: {ma_otp}, hạn {thoi_han_phut} phút. {la}",
            List.of("ten_khach", "ma_otp", "thoi_han_phut"), List.of("ma_otp"));

    @Test
    void replacesEveryOccurrenceOfAllowedVariables() {
        Rendered rendered = renderer.render(OTP, Map.of("ten_khach", "An", "ma_otp", "123456", "thoi_han_phut", 5));

        assertThat(rendered.subject()).isEqualTo("Mã 123456");
        assertThat(rendered.body()).isEqualTo("Chào An,\nMã: 123456, hạn 5 phút. {la}");
    }

    @Test
    void missingOptionalVariableBecomesEmpty() {
        Rendered rendered = renderer.render(OTP, Map.of("ma_otp", "123456", "thoi_han_phut", 5));

        assertThat(rendered.body()).startsWith("Chào ,");
        assertThat(rendered.body()).doesNotContain("{ten_khach}");
    }

    @Test
    void reportsMissingOrNullRequiredVariables() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("ma_otp", null);

        assertThat(renderer.missingRequired(OTP, Map.of("thoi_han_phut", 5))).containsExactly("ma_otp");
        assertThat(renderer.missingRequired(OTP, withNull)).containsExactly("ma_otp");
        assertThat(renderer.missingRequired(OTP, Map.of("ma_otp", "1"))).isEmpty();
    }

    /** Thay chuỗi thuần, không regex: {@code $1} và {@code \} trong giá trị giữ nguyên. */
    @Test
    void valuesWithRegexCharactersAreInsertedLiterally() {
        Rendered rendered = renderer.render(OTP, Map.of("ten_khach", "A$1\\B", "ma_otp", "1", "thoi_han_phut", 5));

        assertThat(rendered.body()).startsWith("Chào A$1\\B,");
    }

    /** D019: giá trị của biến đứng trước chứa token của biến đứng sau không bị thay lần hai. */
    @Test
    void valueContainingTheTokenOfALaterVariableIsNotSubstitutedAgain() {
        Rendered rendered = renderer.render(OTP,
                Map.of("ten_khach", "{ma_otp}", "ma_otp", "123456", "thoi_han_phut", 5));

        assertThat(rendered.body()).startsWith("Chào {ma_otp},\nMã: 123456, hạn 5 phút.");
        assertThat(rendered.body().split("123456", -1)).hasSize(2);
    }

    @Test
    void valueContainingTheTokenOfAnEarlierVariableIsNotSubstitutedAgain() {
        TemplateView template = new TemplateView("X", "EMAIL", "{a}", "{a}|{b}", List.of("a", "b"), List.of());

        Rendered rendered = renderer.render(template, Map.of("a", "1", "b", "{a}"));

        assertThat(rendered.subject()).isEqualTo("1");
        assertThat(rendered.body()).isEqualTo("1|{a}");
    }

    /** Giá trị mở đầu token, chữ của mẫu đóng token: sau khi chèn không được ghép thành một biến. */
    @Test
    void valueAndTemplateTextDoNotFormATokenAcrossTheBoundary() {
        TemplateView template = new TemplateView("X", "EMAIL", null, "{ten_khach}otp} {ma_otp}",
                List.of("ten_khach", "ma_otp"), List.of());

        Rendered rendered = renderer.render(template, Map.of("ten_khach", "{ma_", "ma_otp", "123456"));

        assertThat(rendered.body()).isEqualTo("{ma_otp} 123456");
    }

    /** Ca thật của D019: mẫu {@code ACCOUNT_LOCKED_FOLLOW_UP_APP} (V12), họ tên khách là {@code {ly_do}}. */
    @Test
    void accountLockedTemplateKeepsACustomerNameThatLooksLikeAVariable() {
        TemplateView template = new TemplateView("ACCOUNT_LOCKED_FOLLOW_UP_APP", "IN_APP", "Tài khoản đã bị khóa",
                "Tài khoản {email_tai_khoan} ({ho_ten}, {vai_tro}) đã bị khóa lúc {thoi_diem}.\nLý do: {ly_do}\n\n"
                        + "Việc dở dang:\n{viec_do_dang}",
                List.of("ho_ten", "email_tai_khoan", "vai_tro", "ly_do", "thoi_diem", "viec_do_dang"),
                List.of("email_tai_khoan", "ly_do", "thoi_diem", "viec_do_dang"));

        Rendered rendered = renderer.render(template, Map.of("ho_ten", "{ly_do}", "email_tai_khoan", "a@x.vn",
                "vai_tro", "Khách", "ly_do", "Spam feedback", "thoi_diem", "10:00 01/11/2026", "viec_do_dang", "Không có"));

        assertThat(rendered.body()).startsWith("Tài khoản a@x.vn ({ly_do}, Khách) đã bị khóa lúc 10:00 01/11/2026.\n"
                + "Lý do: Spam feedback\n");
    }

    @Test
    void unknownTokensAndStrayBracesStayLiteral() {
        TemplateView template = new TemplateView("X", "EMAIL", null, "{abc} } {} {ma_{ma_otp}",
                List.of("ma_otp"), List.of());

        assertThat(renderer.render(template, Map.of("ma_otp", "1")).body()).isEqualTo("{abc} } {} {ma_1");
    }

    @Test
    void nullSubjectRendersEmpty() {
        TemplateView noSubject = new TemplateView("X", "EMAIL", null, "b", List.of(), List.of());

        assertThat(renderer.render(noSubject, Map.of()).subject()).isEmpty();
    }
}
