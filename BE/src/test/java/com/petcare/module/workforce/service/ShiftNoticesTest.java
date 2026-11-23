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
 * {@link ShiftNotices} (docs/adr/0149 bước 4): khóa payload khớp {@code required_vars} / {@code allowed_vars} của mẫu
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

    // ---------------------------------------------------------------- vắng đột xuất được ghi nhận (#7, bước 7)

    /** {@code required_vars} của SHIFT_ABSENCE_RECORDED_APP (V40:110-117): không có giờ ca. */
    private static final Set<String> ABSENCE_IN_APP_VARS = Set.of("ten_ca", "ngay_lam", "chi_nhanh", "ly_do");
    /** {@code allowed_vars} của SHIFT_ABSENCE_RECORDED (V40:102-109): thêm lời chào tùy chọn. */
    private static final Set<String> ABSENCE_EMAIL_VARS = Set.of("ten_ca", "ngay_lam", "chi_nhanh", "ly_do",
            "ten_nhan_vien");

    @Test
    void absenceBuildsTheInAppNoticeFirstThenTheEmailWithTheAbsenceTemplates() {
        List<NotificationRequest> requests = absence("an@example.com");

        assertThat(requests).hasSize(2);
        NotificationRequest inApp = requests.get(0);
        assertThat(inApp.templateCode()).isEqualTo(NotificationTemplateCode.SHIFT_ABSENCE_RECORDED_APP);
        assertThat(inApp.channel()).isEqualTo(Channel.IN_APP);
        assertThat(inApp.recipientAccountId()).isEqualTo(12L);
        assertThat(inApp.recipientEmail()).isNull();
        NotificationRequest email = requests.get(1);
        assertThat(email.templateCode()).isEqualTo(NotificationTemplateCode.SHIFT_ABSENCE_RECORDED);
        assertThat(email.channel()).isEqualTo(Channel.EMAIL);
        assertThat(email.recipientEmail()).isEqualTo("an@example.com");
        assertThat(email.recipientAccountId()).isNull();
    }

    @Test
    void absencePayloadKeysAreExactlyTheTemplateVariablesAndCarryNoShiftHours() {
        List<NotificationRequest> requests = absence("an@example.com");

        assertThat(requests.get(0).payload().keySet()).containsExactlyInAnyOrderElementsOf(ABSENCE_IN_APP_VARS);
        assertThat(requests.get(1).payload().keySet()).containsExactlyInAnyOrderElementsOf(ABSENCE_EMAIL_VARS);
        assertThat(requests.get(0).payload()).doesNotContainKeys("gio_bat_dau", "gio_ket_thuc");
    }

    @Test
    void absenceFormatsTheDateLikeTheOtherNoticesAndKeepsTheReasonVerbatim() {
        NotificationRequest inApp = absence("an@example.com").get(0);

        assertThat(inApp.payload()).containsEntry("ngay_lam", "05/12/2026").containsEntry("ten_ca", "Ca sáng")
                .containsEntry("chi_nhanh", "Chi nhánh Quận 1").containsEntry("ly_do", "Nhân viên báo ốm");
        assertThat(absence("an@example.com").get(1).payload()).containsEntry("ten_nhan_vien", "Nguyễn Văn An");
    }

    @Test
    void absenceWithoutAnEmailBuildsOnlyTheInAppNotice() {
        for (String missing : new String[] {null, "", "   "}) {
            assertThat(absence(missing)).as("email=%s", missing).singleElement()
                    .extracting(NotificationRequest::channel).isEqualTo(Channel.IN_APP);
        }
    }

    @Test
    void absenceEveryPayloadValueIsPresentSoNoRequiredVariableRendersEmpty() {
        for (NotificationRequest request : absence("an@example.com")) {
            assertThat(request.payload().values()).doesNotContainNull();
            assertThat(request.payload().values()).allSatisfy(value -> assertThat(value.toString()).isNotBlank());
        }
    }

    /** {@code required_vars} của SHIFT_REQUEST_REJECTED_APP (V40:83-84). */
    private static final Set<String> REJECTED_IN_APP_VARS = Set.of("mo_ta_yeu_cau", "chi_nhanh", "ly_do");
    /** {@code allowed_vars} của SHIFT_REQUEST_REJECTED (V40:75): thêm lời chào tùy chọn. */
    private static final Set<String> REJECTED_EMAIL_VARS = Set.of("mo_ta_yeu_cau", "chi_nhanh", "ly_do",
            "ten_nhan_vien");

    @Test
    void rejectedBuildsTheInAppNoticeFirstThenTheEmailWithTheRejectedTemplates() {
        List<NotificationRequest> requests = rejected("an@example.com");

        assertThat(requests).hasSize(2);
        NotificationRequest inApp = requests.get(0);
        assertThat(inApp.templateCode()).isEqualTo(NotificationTemplateCode.SHIFT_REQUEST_REJECTED_APP);
        assertThat(inApp.channel()).isEqualTo(Channel.IN_APP);
        assertThat(inApp.recipientAccountId()).isEqualTo(12L);
        assertThat(inApp.recipientEmail()).isNull();
        NotificationRequest email = requests.get(1);
        assertThat(email.templateCode()).isEqualTo(NotificationTemplateCode.SHIFT_REQUEST_REJECTED);
        assertThat(email.channel()).isEqualTo(Channel.EMAIL);
        assertThat(email.recipientEmail()).isEqualTo("an@example.com");
        assertThat(email.recipientAccountId()).isNull();
    }

    @Test
    void rejectedPayloadKeysAreExactlyTheTemplateVariablesOfEachChannel() {
        List<NotificationRequest> requests = rejected("an@example.com");

        assertThat(requests.get(0).payload().keySet()).containsExactlyInAnyOrderElementsOf(REJECTED_IN_APP_VARS);
        assertThat(requests.get(1).payload().keySet()).containsExactlyInAnyOrderElementsOf(REJECTED_EMAIL_VARS);
        assertThat(requests.get(0).payload()).containsEntry("mo_ta_yeu_cau", "nghỉ ca Ca sáng ngày 05/12/2026")
                .containsEntry("chi_nhanh", "Chi nhánh Quận 1").containsEntry("ly_do", "Ca này đã đủ người nghỉ");
        assertThat(requests.get(1).payload()).containsEntry("ten_nhan_vien", "Nguyễn Văn An");
    }

    @Test
    void rejectedWithoutAnEmailBuildsOnlyTheInAppNotice() {
        for (String missing : new String[] {null, "", "   "}) {
            assertThat(rejected(missing)).as("email=%s", missing).singleElement()
                    .extracting(NotificationRequest::channel).isEqualTo(Channel.IN_APP);
        }
    }

    /** Biến của mẫu duyệt ở V40: không có {@code ly_do}. */
    private static final Set<String> APPROVED_IN_APP_VARS = Set.of("mo_ta_yeu_cau", "chi_nhanh");
    private static final Set<String> APPROVED_EMAIL_VARS = Set.of("mo_ta_yeu_cau", "chi_nhanh", "ten_nhan_vien");

    @Test
    void approvedBuildsTheInAppNoticeFirstThenTheEmailWithTheApprovedTemplates() {
        List<NotificationRequest> requests = approved("an@example.com");

        assertThat(requests).hasSize(2);
        NotificationRequest inApp = requests.get(0);
        assertThat(inApp.templateCode()).isEqualTo(NotificationTemplateCode.SHIFT_REQUEST_APPROVED_APP);
        assertThat(inApp.channel()).isEqualTo(Channel.IN_APP);
        assertThat(inApp.recipientAccountId()).isEqualTo(12L);
        assertThat(inApp.recipientEmail()).isNull();
        NotificationRequest email = requests.get(1);
        assertThat(email.templateCode()).isEqualTo(NotificationTemplateCode.SHIFT_REQUEST_APPROVED);
        assertThat(email.channel()).isEqualTo(Channel.EMAIL);
        assertThat(email.recipientEmail()).isEqualTo("an@example.com");
        assertThat(email.recipientAccountId()).isNull();
    }

    @Test
    void approvedPayloadKeysAreExactlyTheTemplateVariablesOfEachChannelAndCarryNoReason() {
        List<NotificationRequest> requests = approved("an@example.com");

        assertThat(requests.get(0).payload().keySet()).containsExactlyInAnyOrderElementsOf(APPROVED_IN_APP_VARS);
        assertThat(requests.get(1).payload().keySet()).containsExactlyInAnyOrderElementsOf(APPROVED_EMAIL_VARS);
        assertThat(requests.get(0).payload()).containsEntry("mo_ta_yeu_cau", "nghỉ ca Ca sáng ngày 05/12/2026")
                .containsEntry("chi_nhanh", "Chi nhánh Quận 1");
        assertThat(requests.get(1).payload()).containsEntry("ten_nhan_vien", "Nguyễn Văn An");
    }

    @Test
    void approvedWithoutAnEmailBuildsOnlyTheInAppNotice() {
        for (String missing : new String[] {null, "", "   "}) {
            assertThat(approved(missing)).as("email=%s", missing).singleElement()
                    .extracting(NotificationRequest::channel).isEqualTo(Channel.IN_APP);
        }
    }

    @Test
    void describeRequestIsTheSameSentenceAsTheFrontEndMock() {
        LocalDate day = LocalDate.of(2026, 11, 25);

        assertThat(ShiftNotices.describeRequest(ShiftRequestType.LEAVE, "Ca sáng", null, day))
                .isEqualTo("nghỉ ca Ca sáng ngày 25/11/2026");
        assertThat(ShiftNotices.describeRequest(ShiftRequestType.SWAP, "Ca sáng", "Ca chiều", day))
                .isEqualTo("đổi ca Ca sáng sang ca Ca chiều ngày 25/11/2026");
    }

    @Test
    void describeRequestOfASwapNeedsTheTargetName() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ShiftNotices.describeRequest(ShiftRequestType.SWAP,
                "Ca sáng", null, LocalDate.of(2026, 11, 25))).isInstanceOf(NullPointerException.class);
    }

    private static List<NotificationRequest> rejected(String email) {
        return ShiftNotices.rejected(12L, email, "Nguyễn Văn An",
                ShiftNotices.describeRequest(ShiftRequestType.LEAVE, "Ca sáng", null, LocalDate.of(2026, 12, 5)),
                "Chi nhánh Quận 1", "Ca này đã đủ người nghỉ");
    }

    private static List<NotificationRequest> approved(String email) {
        return ShiftNotices.approved(12L, email, "Nguyễn Văn An",
                ShiftNotices.describeRequest(ShiftRequestType.LEAVE, "Ca sáng", null, LocalDate.of(2026, 12, 5)),
                "Chi nhánh Quận 1");
    }

    private static List<NotificationRequest> absence(String email) {
        return ShiftNotices.absence(12L, email, "Nguyễn Văn An", "Ca sáng", LocalDate.of(2026, 12, 5),
                "Chi nhánh Quận 1", "Nhân viên báo ốm");
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
