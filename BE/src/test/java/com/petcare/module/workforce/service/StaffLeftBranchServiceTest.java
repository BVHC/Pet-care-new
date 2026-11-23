package com.petcare.module.workforce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository.CancellableAssignment;
import com.petcare.module.workforce.repository.ShiftRequestRepository;
import com.petcare.platform.config.TimeConfig;

/**
 * {@link StaffLeftBranchService} (BR-NS-09, docs/adr/0149 bước 5): thứ tự chọn+khóa → UPDATE phân ca → UPDATE yêu cầu →
 * thông báo; rỗng thì không ghi; chi nhánh trong thông báo là chi nhánh <b>cũ</b> của sự kiện; mọi lỗi dữ liệu hỏng là
 * {@code IllegalStateException} (rút cả thao tác gốc); cấu hình transaction {@code MANDATORY}.
 */
class StaffLeftBranchServiceTest {

    private static final long STAFF = 12L;
    private static final long OLD_BRANCH = 5L;
    private static final long NEW_BRANCH = 6L;
    /** 10:00 giờ Việt Nam ngày 19/11/2026. */
    private static final Instant NOW = Instant.parse("2026-11-19T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private static final LocalDateTime LOCAL_NOW = LocalDateTime.of(2026, 11, 19, 10, 0);

    private final ShiftAssignmentRepository assignments = mock(ShiftAssignmentRepository.class);
    private final ShiftRequestRepository requests = mock(ShiftRequestRepository.class);
    private final StaffDirectoryApi staffDirectory = mock(StaffDirectoryApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final NotificationApi notifications = mock(NotificationApi.class);

    private StaffLeftBranchService service;

    @BeforeEach
    void setUp() {
        service = new StaffLeftBranchService(assignments, requests, staffDirectory, branches, notifications, CLOCK);
        // Sau điều chuyển trong cùng transaction, StaffSummary.branchId ĐÃ là chi nhánh mới: bẫy mà service phải tránh.
        when(staffDirectory.findStaff(STAFF)).thenReturn(Optional.of(
                new StaffSummary(STAFF, "Nguyễn Văn An", Role.VET, NEW_BRANCH, true, false)));
        when(staffDirectory.findEmail(STAFF)).thenReturn(Optional.of("an@example.com"));
        when(branches.findBranch(OLD_BRANCH)).thenReturn(Optional.of(branch(OLD_BRANCH, "Chi nhánh cũ")));
        when(branches.findBranch(NEW_BRANCH)).thenReturn(Optional.of(branch(NEW_BRANCH, "Chi nhánh mới")));
    }

    // ---------------------------------------------------------------- thứ tự và kết quả

    @Test
    void locksThenCancelsAssignmentsThenRejectsRequestsThenNotifiesEachShift() {
        givenLocked(row(31L, "Ca sáng", 8, 12), row(32L, "Ca chiều", 13, 17));
        when(assignments.cancelByStaffLeaving(any(), any(), any())).thenReturn(2);

        int cancelled = service.cancelUpcomingShiftsOf(STAFF, OLD_BRANCH);

        assertThat(cancelled).isEqualTo(2);
        InOrder order = inOrder(assignments, requests, staffDirectory, branches, notifications);
        order.verify(assignments).lockNotStartedScheduled(STAFF, OLD_BRANCH, TODAY, LOCAL_NOW);
        order.verify(assignments).cancelByStaffLeaving(List.of(31L, 32L), StaffLeftBranchService.LEFT_BRANCH_REASON, NOW);
        order.verify(requests).rejectPendingBySystem(List.of(31L, 32L), StaffLeftBranchService.LEFT_BRANCH_REASON, NOW);
        order.verify(staffDirectory).findStaff(STAFF);
        order.verify(branches).findBranch(OLD_BRANCH);
        order.verify(notifications, org.mockito.Mockito.times(4)).enqueue(any());
    }

    @Test
    void eachCancelledShiftGetsItsOwnInAppThenEmailPairInShiftOrder() {
        givenLocked(row(31L, "Ca sáng", 8, 12), row(32L, "Ca chiều", 13, 17));
        when(assignments.cancelByStaffLeaving(any(), any(), any())).thenReturn(2);

        service.cancelUpcomingShiftsOf(STAFF, OLD_BRANCH);

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications, org.mockito.Mockito.times(4)).enqueue(sent.capture());
        List<NotificationRequest> all = sent.getAllValues();
        assertThat(all).extracting(NotificationRequest::channel)
                .containsExactly(Channel.IN_APP, Channel.EMAIL, Channel.IN_APP, Channel.EMAIL);
        assertThat(all.get(0).payload()).containsEntry("ten_ca", "Ca sáng").containsEntry("ngay_lam", "24/11/2026")
                .containsEntry("gio_bat_dau", "08:00").containsEntry("gio_ket_thuc", "12:00");
        assertThat(all.get(2).payload()).containsEntry("ten_ca", "Ca chiều").containsEntry("gio_bat_dau", "13:00");
        assertThat(all.get(0).recipientAccountId()).as("người nhận là nhân viên bị ảnh hưởng").isEqualTo(STAFF);
        assertThat(all.get(1).recipientEmail()).isEqualTo("an@example.com");
        assertThat(all.get(1).payload()).containsEntry("ten_nhan_vien", "Nguyễn Văn An");
        assertThat(all).allSatisfy(request -> assertThat(request.payload())
                .containsEntry("ly_do", StaffLeftBranchService.LEFT_BRANCH_REASON));
    }

    @Test
    void theBranchNameInTheNoticeIsTheOldBranchOfTheEventNeverTheStaffsCurrentBranch() {
        givenLocked(row(31L, "Ca sáng", 8, 12));
        when(assignments.cancelByStaffLeaving(any(), any(), any())).thenReturn(1);

        service.cancelUpcomingShiftsOf(STAFF, OLD_BRANCH);

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications, org.mockito.Mockito.times(2)).enqueue(sent.capture());
        assertThat(sent.getAllValues()).allSatisfy(request -> assertThat(request.payload())
                .containsEntry("chi_nhanh", "Chi nhánh cũ"));
        verify(branches, never()).findBranch(NEW_BRANCH);
    }

    @Test
    void withoutAnEmailOnlyTheInAppNoticeIsSentPerShift() {
        givenLocked(row(31L, "Ca sáng", 8, 12), row(32L, "Ca chiều", 13, 17));
        when(assignments.cancelByStaffLeaving(any(), any(), any())).thenReturn(2);
        when(staffDirectory.findEmail(STAFF)).thenReturn(Optional.empty());

        service.cancelUpcomingShiftsOf(STAFF, OLD_BRANCH);

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications, org.mockito.Mockito.times(2)).enqueue(sent.capture());
        assertThat(sent.getAllValues()).extracting(NotificationRequest::channel)
                .containsOnly(Channel.IN_APP);
    }

    @Test
    void noUpcomingShiftsMeansNothingToDoAndNoWriteNoLookupNoNotice() {
        when(assignments.lockNotStartedScheduled(STAFF, OLD_BRANCH, TODAY, LOCAL_NOW)).thenReturn(List.of());

        assertThat(service.cancelUpcomingShiftsOf(STAFF, OLD_BRANCH)).isZero();

        verify(assignments, never()).cancelByStaffLeaving(anyCollection(), any(), any());
        verifyNoInteractions(requests, staffDirectory, branches, notifications);
    }

    // ---------------------------------------------------------------- lỗi: luôn là lỗi hạ tầng / dữ liệu hỏng

    @Test
    void aMismatchBetweenLockedAndUpdatedRowsBreaksTheInvariantAndStopsBeforeRequestsAndNotices() {
        givenLocked(row(31L, "Ca sáng", 8, 12), row(32L, "Ca chiều", 13, 17));
        when(assignments.cancelByStaffLeaving(any(), any(), any())).thenReturn(1);

        assertThatThrownBy(() -> service.cancelUpcomingShiftsOf(STAFF, OLD_BRANCH))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(requests, notifications);
    }

    @Test
    void aMissingStaffOrBranchIsCorruptDataNotABusinessError() {
        givenLocked(row(31L, "Ca sáng", 8, 12));
        when(assignments.cancelByStaffLeaving(any(), any(), any())).thenReturn(1);
        when(staffDirectory.findStaff(STAFF)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancelUpcomingShiftsOf(STAFF, OLD_BRANCH))
                .isInstanceOf(IllegalStateException.class);

        when(staffDirectory.findStaff(STAFF)).thenReturn(Optional.of(
                new StaffSummary(STAFF, "Nguyễn Văn An", Role.VET, NEW_BRANCH, true, false)));
        when(branches.findBranch(OLD_BRANCH)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancelUpcomingShiftsOf(STAFF, OLD_BRANCH))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(notifications);
    }

    @Test
    void theEventMustCarryBothIds() {
        assertThatThrownBy(() -> service.cancelUpcomingShiftsOf(null, OLD_BRANCH))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.cancelUpcomingShiftsOf(STAFF, null))
                .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(assignments, requests, notifications);
    }

    @Test
    void theSystemReasonFitsTheTrackedColumnsAndIsNeverBlank() {
        assertThat(StaffLeftBranchService.LEFT_BRANCH_REASON).isNotBlank().hasSizeLessThanOrEqualTo(300);
        assertThat(StaffLeftBranchService.LEFT_BRANCH_REASON).isEqualTo(StaffLeftBranchService.LEFT_BRANCH_REASON.strip());
    }

    // ---------------------------------------------------------------- transaction

    /**
     * Tham gia transaction của identity, không tự mở: {@code MANDATORY}. Không đặt {@code isolation} (READ COMMITTED mặc
     * định: câu khóa phải thấy dòng đã commit sau khi chờ khóa) và không {@code noRollbackFor}.
     */
    @Test
    void joinsTheCallersTransactionAtDefaultIsolationAndRollsBackOnEveryException() throws Exception {
        Transactional tx = StaffLeftBranchService.class.getMethod("cancelUpcomingShiftsOf", Long.class, Long.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(tx.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.noRollbackFor()).isEmpty();
        assertThat(tx.noRollbackForClassName()).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private void givenLocked(CancellableAssignment... rows) {
        when(assignments.lockNotStartedScheduled(STAFF, OLD_BRANCH, TODAY, LOCAL_NOW)).thenReturn(List.of(rows));
    }

    private static BranchSummary branch(long id, String name) {
        return new BranchSummary(id, name, "1 Lê Lợi", "0900000000", BranchStatus.ACTIVE, false);
    }

    /** Lớp ẩn danh, không dùng mock: projection native của Spring Data chỉ là interface getter. */
    private static CancellableAssignment row(long id, String shiftName, int startHour, int endHour) {
        return new CancellableAssignment() {
            @Override
            public Long getAssignmentId() {
                return id;
            }

            @Override
            public LocalDate getWorkDate() {
                return LocalDate.of(2026, 11, 24);
            }

            @Override
            public String getShiftName() {
                return shiftName;
            }

            @Override
            public LocalTime getStartTime() {
                return LocalTime.of(startHour, 0);
            }

            @Override
            public LocalTime getEndTime() {
                return LocalTime.of(endHour, 0);
            }
        };
    }
}
