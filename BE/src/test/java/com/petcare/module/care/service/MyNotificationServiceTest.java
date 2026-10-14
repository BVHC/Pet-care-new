package com.petcare.module.care.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mapstruct.factory.Mappers;
import org.mockito.InOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.dto.NotificationResponse;
import com.petcare.module.care.dto.UnreadCountResponse;
import com.petcare.module.care.entity.Notification;
import com.petcare.module.care.mapper.NotificationMapper;
import com.petcare.module.care.repository.NotificationRepository;
import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * care-v1 #5–8 (UC88; docs/adr/0039): chủ luôn lấy từ token, hai câu đọc tách theo {@code unreadOnly}, kẹp phân trang,
 * đánh dấu đã đọc bằng một câu UPDATE và chỉ hỏi tồn tại khi không đổi dòng nào, 404 cùng message, giờ từ {@code Clock}.
 */
class MyNotificationServiceTest {

    private static final long ME = 7L;
    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationMapper mapper = Mappers.getMapper(NotificationMapper.class);
    private final BranchScope branchScope = mock(BranchScope.class);
    private final MyNotificationService service = new MyNotificationService(repository, mapper, branchScope,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        SecurityPrincipal principal = mock(SecurityPrincipal.class);
        when(principal.accountId()).thenReturn(ME);
        when(branchScope.current()).thenReturn(principal);
    }

    // ---------------------------------------------------------------- #5

    @Test
    void listsAllOfMineByDefaultAndMapsEveryRow() {
        Notification row = notification(11L, null);
        when(repository.findMine(ME, PageRequest.of(1, 20)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(1, 20), 21));

        PageResponse<NotificationResponse> page = service.listMyNotifications(null, 1, 20);

        assertThat(page).isEqualTo(new PageResponse<>(List.of(mapper.toResponse(row)), 1, 20, 21, 2));
        verify(repository, never()).findMineUnread(anyLong(), any());
    }

    @Test
    void unreadOnlyFalseIsTheFullList() {
        when(repository.findMine(ME, PageRequest.of(0, 20))).thenReturn(Page.empty(PageRequest.of(0, 20)));

        assertThat(service.listMyNotifications(false, 0, 20).content()).isEmpty();
        verify(repository, never()).findMineUnread(anyLong(), any());
    }

    @Test
    void unreadOnlyTrueUsesTheUnreadQuery() {
        when(repository.findMineUnread(ME, PageRequest.of(0, 20))).thenReturn(Page.empty(PageRequest.of(0, 20)));

        service.listMyNotifications(true, 0, 20);

        verify(repository).findMineUnread(ME, PageRequest.of(0, 20));
        verify(repository, never()).findMine(anyLong(), any());
    }

    @ParameterizedTest
    @CsvSource({"-1, 0, 0, 1", "0, 1, 0, 1", "3, 100, 3, 100", "0, 101, 0, 100", "0, 2147483647, 0, 100",
            "-2147483648, -5, 0, 1"})
    void clampsPageAndSize(int page, int size, int expectedPage, int expectedSize) {
        Pageable expected = PageRequest.of(expectedPage, expectedSize);
        when(repository.findMine(ME, expected)).thenReturn(Page.empty(expected));

        service.listMyNotifications(null, page, size);

        verify(repository).findMine(ME, expected);
    }

    // ---------------------------------------------------------------- #6

    @Test
    void countsMyUnread() {
        when(repository.countUnread(ME)).thenReturn(3L);

        assertThat(service.countMyUnreadNotifications()).isEqualTo(new UnreadCountResponse(3));
    }

    // ---------------------------------------------------------------- #7

    @Test
    void markReadThatChangedARowDoesNotAskForExistence() {
        when(repository.markRead(5L, ME, NOW)).thenReturn(1);

        service.markNotificationRead(5L);

        verify(repository).markRead(5L, ME, NOW);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void markReadOfAlreadyReadNotificationSucceeds() {
        when(repository.markRead(5L, ME, NOW)).thenReturn(0);
        when(repository.existsByIdAndAccountId(5L, ME)).thenReturn(true);

        service.markNotificationRead(5L);

        InOrder order = inOrder(repository);
        order.verify(repository).markRead(5L, ME, NOW);
        order.verify(repository).existsByIdAndAccountId(5L, ME);
    }

    @Test
    void markReadOfMissingOrForeignNotificationIs404WithOneMessage() {
        when(repository.markRead(5L, ME, NOW)).thenReturn(0);
        when(repository.existsByIdAndAccountId(5L, ME)).thenReturn(false);

        assertThatThrownBy(() -> service.markNotificationRead(5L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy thông báo #5")
                .satisfies(ex -> assertThat(((ResourceNotFoundException) ex).errorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // ---------------------------------------------------------------- #8

    @Test
    void markAllReadUsesMyAccountAndTheClock() {
        when(repository.markAllRead(ME, NOW)).thenReturn(4);

        service.markAllNotificationsRead();

        verify(repository).markAllRead(ME, NOW);
        verifyNoMoreInteractions(repository);
    }

    // ---------------------------------------------------------------- transaction

    @Test
    void readsAreReadOnlyDefaultIsolationAndWritesAreDefaultTransactions() throws NoSuchMethodException {
        assertTx("listMyNotifications", true, Boolean.class, int.class, int.class);
        assertTx("countMyUnreadNotifications", true);
        assertTx("markNotificationRead", false, Long.class);
        assertTx("markAllNotificationsRead", false);
        assertThat(MyNotificationService.class.getAnnotation(Transactional.class)).isNull();
    }

    private static void assertTx(String method, boolean readOnly, Class<?>... types) throws NoSuchMethodException {
        Transactional tx = MyNotificationService.class.getMethod(method, types).getAnnotation(Transactional.class);
        assertThat(tx).as(method).isNotNull();
        assertThat(tx.readOnly()).as(method + " readOnly").isEqualTo(readOnly);
        assertThat(tx.propagation()).as(method).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.isolation()).as(method).isEqualTo(Isolation.DEFAULT);
        assertThat(tx.noRollbackFor()).as(method).isEmpty();
    }

    private static Notification notification(long id, Instant readAt) {
        Notification notification = Notification.deliver(ME, "ACCOUNT_LOCKED_FOLLOW_UP", "Tiêu đề", "Nội dung",
                "/admin/accounts/3");
        ReflectionTestUtils.setField(notification, "id", id);
        ReflectionTestUtils.setField(notification, "readAt", readAt);
        ReflectionTestUtils.setField(notification, "createdAt", NOW.minusSeconds(60));
        return notification;
    }
}
