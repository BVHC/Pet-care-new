package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.PendingWorkItem;
import com.petcare.module.identity.dto.PendingWorkItem.PendingWorkType;
import com.petcare.module.identity.service.AccountLockNotices.LockedAccount;

/**
 * Nội dung hai mẫu IN_APP của khóa tài khoản (V12; docs/adr/0035): đủ nhãn cho 7 role và 6 loại việc dở, payload đúng bộ
 * khóa của mẫu, thời điểm theo giờ Việt Nam. Khớp với dòng mẫu trong DB kiểm ở {@code AccountLockIT}.
 */
class AccountLockNoticesTest {

    private static final LockedAccount LOCKED = new LockedAccount("Trần Thị Lễ Tân", "rc@petcare.test",
            Role.RECEPTIONIST, "Vi phạm", Instant.parse("2026-10-12T16:30:00Z"));

    @ParameterizedTest
    @EnumSource(Role.class)
    void everyRoleHasVietnameseLabel(Role role) {
        assertThat(AccountLockNotices.roleLabel(role)).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(PendingWorkType.class)
    void everyWorkTypeHasLineWithId(PendingWorkType type) {
        assertThat(AccountLockNotices.describe(List.of(new PendingWorkItem(type, 987L, 1L, false))))
                .startsWith("- ").contains("#987");
    }

    @Test
    void emptyWorkIsNoneAndLinesKeepOrder() {
        assertThat(AccountLockNotices.describe(List.of())).isEqualTo("Không có");
        assertThat(AccountLockNotices.describe(List.of(
                new PendingWorkItem(PendingWorkType.VISIT_IN_PROGRESS, 1L, 1L, true),
                new PendingWorkItem(PendingWorkType.ORDER_UNPAID, 2L, 1L, false))))
                .isEqualTo("- Lượt tiếp nhận #1: đang thực hiện, cần gán lại\n- Order #2: chưa thanh toán");
    }

    @Test
    void followUpPayloadAndAddressing() {
        NotificationRequest notice = AccountLockNotices.followUp(5L, LOCKED, List.of());

        assertThat(notice.templateCode()).isEqualTo(NotificationTemplateCode.ACCOUNT_LOCKED_FOLLOW_UP_APP);
        assertThat(notice.channel()).isEqualTo(Channel.IN_APP);
        assertThat(notice.recipientAccountId()).isEqualTo(5L);
        assertThat(notice.recipientEmail()).isNull();
        assertThat(notice.linkUrl()).isNull();
        // 16:30Z = 23:30 giờ Việt Nam cùng ngày.
        assertThat(notice.payload()).containsOnlyKeys("ho_ten", "email_tai_khoan", "vai_tro", "ly_do", "thoi_diem",
                "viec_do_dang").containsEntry("thoi_diem", "23:30 12/10/2026").containsEntry("vai_tro", "Lễ tân");
    }

    @Test
    void lastManagerPayloadAddsBranchName() {
        NotificationRequest notice = AccountLockNotices.lastBranchManager(6L, LOCKED, "CN Quận 1", List.of());

        assertThat(notice.templateCode()).isEqualTo(NotificationTemplateCode.LAST_BRANCH_MANAGER_LOCKED_APP);
        assertThat(notice.payload()).containsOnlyKeys("ho_ten", "email_tai_khoan", "vai_tro", "ly_do", "thoi_diem",
                "viec_do_dang", "ten_chi_nhanh").containsEntry("ten_chi_nhanh", "CN Quận 1");
    }
}
