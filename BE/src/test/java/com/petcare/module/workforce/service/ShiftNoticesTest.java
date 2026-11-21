package com.petcare.module.workforce.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.workforce.entity.ShiftRequestType;

/**
 * {@link ShiftNotices} (docs/adr/0138 bước 4): khóa payload khớp {@code required_vars} / {@code allowed_vars} của mẫu
 * V40 (so với bảng mẫu thật ở {@code ShiftAssignmentCancelIT}), định dạng ngày giờ, và chỉ dựng chứ không gửi.
 */
class ShiftNoticesTest {

    /** {@code required_vars} của SHIFT_ASSIGNMENT_CANCELLED_APP (V40:99). */
    private static final Set<String> IN_APP_VARS = Set.of("ten_ca", "ngay_lam", "gio_bat_dau", "gio_ket_thuc",
            "chi_nhanh", "ly_do");
    /** {@code allowed_vars} của SHIFT_ASSIGNMENT_CANCELLED (V40:91): thêm lời chào tùy chọn. */
    private static final Set<String> EMAIL_VARS = Set.of("ten_ca", "ngay_lam", "gio_bat_dau", "gio_ket_thuc",
            "chi_nhanh", "ly_do", "ten_nhan_vien");

    @Test
    void anAccountWithAnEmailGetsTheInAppNoticeFirstThenTheEmail() {
        List<NotificationRequest> requests = cancelled("an@example.com");

        assertThat(requests).hasSize(2);
        NotificationRequest inApp = requests.get(0);
        assertThat(inApp.templateCode()).isEqualTo(NotificationTemplateCode.SHIFT_ASSIGNMENT_CANCELLED_APP);
        assertThat(inApp.channel()).isEqualTo(Channel.IN_APP);
        assertThat(inApp.recipientAccountId()).isEqualTo(12L);
        assertThat(inApp.recipientEmail()).isNull();
        NotificationRequest email = requests.get(1);
        assertThat(email.templateCode()).isEqualTo(NotificationTemplateCode.SHIFT_ASSIGNMENT_CANCELLED);
        assertThat(email.channel()).isEqualTo(Channel.EMAIL);
        assertThat(email.recipientEmail()).isEqualTo("an@example.com");
        assertThat(email.recipientAccountId()).isNull();
    }

    @Test
    void payloadKeysAreExactlyTheTemplateVariablesOfEachChannel() {
        List<NotificationRequest> requests = cancelled("an@example.com");

        assertThat(requests.get(0).payload().keySet()).containsExactlyInAnyOrderElementsOf(IN_APP_VARS);
        assertThat(requests.get(1).payload().keySet()).containsExactlyInAnyOrderElementsOf(EMAIL_VARS);
    }

    @Test
    void datesAndTimesAreFormattedTheWayTheOtherNoticesDo() {
        NotificationRequest inApp = cancelled("an@example.com").get(0);

        assertThat(inApp.payload()).containsEntry("ngay_lam", "05/12/2026").containsEntry("gio_bat_dau", "08:05")
                .containsEntry("gio_ket_thuc", "12:30").containsEntry("ten_ca", "Ca sáng")
                .containsEntry("chi_nhanh", "Chi nhánh Quận 1").containsEntry("ly_do", "Nghỉ lễ");
        assertThat(cancelled("an@example.com").get(1).payload()).containsEntry("ten_nhan_vien", "Nguyễn Văn An");
    }

    @Test
    void withoutAnEmailOnlyTheInAppNoticeIsBuiltBecauseEnqueueRejectsAnEmailChannelWithoutAddress() {
        for (String missing : new String[] {null, "", "   "}) {
            List<NotificationRequest> requests = cancelled(missing);

            assertThat(requests).as("email=%s", missing).singleElement()
                    .extracting(NotificationRequest::channel).isEqualTo(Channel.IN_APP);
        }
    }

    @Test
    void everyPayloadValueIsPresentSoNoRequiredVariableRendersEmpty() {
        for (NotificationRequest request : cancelled("an@example.com")) {
            assertThat(request.payload().values()).doesNotContainNull();
            assertThat(request.payload().values()).allSatisfy(value -> assertThat(value.toString()).isNotBlank());
        }
    }

    // ---------------------------------------------------------------- yêu cầu nghỉ / đổi ca mới (#9, bước 6)

    /** {@code required_vars} = {@code allowed_vars} của SHIFT_REQUEST_SUBMITTED_APP (V40:46-52). */
    private static final Set<String> SUBMITTED_VARS = Set.of("ten_nhan_vien", "loai_yeu_cau", "ten_ca", "ngay_lam",
            "chi_nhanh", "ly_do");

    @Test
    void submittedBuildsOneInAppNoticePerManagerWithExactlyTheTemplateVariables() {
        List<NotificationRequest> requests = submitted(List.of(90L, 91L), ShiftRequestType.LEAVE);

        assertThat(requests).hasSize(2);
        assertThat(requests).extracting(NotificationRequest::recipientAccountId).containsExactly(90L, 91L);
        for (NotificationRequest request : requests) {
            assertThat(request.templateCode()).isEqualTo(NotificationTemplateCode.SHIFT_REQUEST_SUBMITTED_APP);
            assertThat(request.channel()).isEqualTo(Channel.IN_APP);
            assertThat(request.recipientEmail()).isNull();
            assertThat(request.payload().keySet()).containsExactlyInAnyOrderElementsOf(SUBMITTED_VARS);
            assertThat(request.payload().values()).allSatisfy(value -> assertThat(value.toString()).isNotBlank());
        }
    }

    @Test
    void submittedSaysNghiForALeaveAndDoiForASwapAndFormatsTheDateLikeTheOtherNotices() {
        assertThat(submitted(List.of(90L), ShiftRequestType.LEAVE).get(0).payload())
                .containsEntry("loai_yeu_cau", "nghỉ").containsEntry("ngay_lam", "05/12/2026")
                .containsEntry("ten_nhan_vien", "Nguyễn Văn An").containsEntry("ten_ca", "Ca sáng")
                .containsEntry("chi_nhanh", "Chi nhánh Quận 1").containsEntry("ly_do", "Việc gia đình");
        assertThat(submitted(List.of(90L), ShiftRequestType.SWAP).get(0).payload()).containsEntry("loai_yeu_cau", "đổi");
    }

    @Test
    void submittedForNoManagerBuildsNothing() {
        assertThat(submitted(List.of(), ShiftRequestType.LEAVE)).isEmpty();
    }

    private static List<NotificationRequest> submitted(List<Long> managers, ShiftRequestType type) {
        return ShiftNotices.submitted(managers, "Nguyễn Văn An", type, "Ca sáng", LocalDate.of(2026, 12, 5),
                "Chi nhánh Quận 1", "Việc gia đình");
    }

    private static List<NotificationRequest> cancelled(String email) {
        return ShiftNotices.cancelled(12L, email, "Nguyễn Văn An", "Ca sáng", LocalDate.of(2026, 12, 5),
                LocalTime.of(8, 5), LocalTime.of(12, 30), "Chi nhánh Quận 1", "Nghỉ lễ");
    }
}
