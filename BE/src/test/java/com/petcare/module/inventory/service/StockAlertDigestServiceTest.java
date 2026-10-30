package com.petcare.module.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.inventory.api.StockReportApi;
import com.petcare.module.inventory.api.StockReportApi.ExpiringLotRow;
import com.petcare.module.inventory.api.StockReportApi.LowStockRow;

/** ST08 (BR-KO-07; docs/adr/0087): đếm đúng ba loại cảnh báo, gửi một thông báo mỗi người nhận. */
class StockAlertDigestServiceTest {

    private static final long BRANCH = 7L;
    /** 2026-10-30 07:00 giờ Việt Nam. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC);

    private final StockReportApi stock = mock(StockReportApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final StockAlertRecipients recipients = mock(StockAlertRecipients.class);
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final StockAlertDigestService service = new StockAlertDigestService(stock, branches, recipients,
            notifications, configs, CLOCK);

    private static ExpiringLotRow lot(long id, String expiry) {
        return new ExpiringLotRow(BRANCH, 5L, id, "L" + id, LocalDate.parse(expiry), 3);
    }

    private void alerts() {
        when(configs.getInt(ConfigKey.STOCK_EXPIRY_WARNING_DAYS)).thenReturn(30);
        when(stock.lowStock(BRANCH)).thenReturn(List.of(new LowStockRow(BRANCH, 5L, 1, 10),
                new LowStockRow(BRANCH, 6L, 0, 3)));
        when(stock.expiringLots(BRANCH, 30)).thenReturn(List.of(lot(1, "2026-10-29"), lot(2, "2026-11-10"),
                lot(3, "2026-11-20"), lot(4, "2026-10-30")));
    }

    @Test
    void noAlertsSendsNothing() {
        when(configs.getInt(ConfigKey.STOCK_EXPIRY_WARNING_DAYS)).thenReturn(30);
        when(stock.lowStock(BRANCH)).thenReturn(List.of());
        when(stock.expiringLots(BRANCH, 30)).thenReturn(List.of());

        assertThat(service.notifyBranch(BRANCH, "CN Quận 1")).isFalse();

        verifyNoInteractions(recipients, notifications);
    }

    @Test
    void sendsOneDigestPerRecipientWithCounts() {
        alerts();
        when(recipients.forBranch(BRANCH)).thenReturn(List.of(11L, 12L));

        assertThat(service.notifyBranch(BRANCH, "CN Quận 1")).isTrue();

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications, times(2)).enqueue(sent.capture());
        NotificationRequest first = sent.getAllValues().get(0);
        assertThat(first.templateCode()).isEqualTo("STOCK_ALERT_DIGEST_APP");
        assertThat(first.channel()).isEqualTo(Channel.IN_APP);
        assertThat(first.recipientAccountId()).isEqualTo(11L);
        assertThat(first.linkUrl()).isEqualTo("/admin/reports");
        assertThat(first.payload().keySet())
                .isEqualTo(Set.of("chi_nhanh", "so_duoi_nguong", "so_sap_het_han", "so_ngay", "so_da_het_han"));
        // đã hết hạn: 29/10; sắp hết hạn: 30/10 (đúng hôm nay vẫn dùng được), 10/11, 20/11
        assertThat(first.payload()).containsEntry("chi_nhanh", "CN Quận 1").containsEntry("so_duoi_nguong", "2")
                .containsEntry("so_sap_het_han", "3").containsEntry("so_da_het_han", "1")
                .containsEntry("so_ngay", "30");
        assertThat(sent.getAllValues().get(1).recipientAccountId()).isEqualTo(12L);
    }

    @Test
    void onlyExpiredLotsStillAreAnAlert() {
        when(configs.getInt(ConfigKey.STOCK_EXPIRY_WARNING_DAYS)).thenReturn(30);
        when(stock.lowStock(BRANCH)).thenReturn(List.of());
        when(stock.expiringLots(BRANCH, 30)).thenReturn(List.of(lot(1, "2026-10-01")));
        when(recipients.forBranch(BRANCH)).thenReturn(List.of(11L));

        assertThat(service.notifyBranch(BRANCH, "CN Quận 1")).isTrue();
    }

    @Test
    void noRecipientSendsNothing() {
        alerts();
        when(recipients.forBranch(BRANCH)).thenReturn(List.of());

        assertThat(service.notifyBranch(BRANCH, "CN Quận 1")).isFalse();

        verify(notifications, never()).enqueue(any());
    }
}
