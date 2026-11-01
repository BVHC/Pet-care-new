package com.petcare.module.inventory.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.petcare.module.inventory.dto.InventoryItemResponse;
import com.petcare.module.inventory.dto.StockLotResponse;

/**
 * Cờ {@code expiringSoon} của dòng tồn và của lô (docs/adr/0088): hàm thuần, rồi hai mapper đọc đúng cột. Mốc
 * {@code WARN} = 2026-10-30 + 30 ngày.
 */
class InventoryQueryRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);
    private static final LocalDate WARN = TODAY.plusDays(30);

    // ---------------------------------------------------------------- dòng tồn

    @Test
    void itemWithStockInAnExpiredLotIsExpiringSoonEvenWithoutAnyUpcomingLot() {
        assertThat(InventoryQueryRepository.itemExpiringSoon(4, null, WARN)).isTrue();
    }

    @Test
    void itemWhoseNearestLotExpiresOnTheWarnDateIsExpiringSoon() {
        assertThat(InventoryQueryRepository.itemExpiringSoon(0, WARN, WARN)).isTrue();
        assertThat(InventoryQueryRepository.itemExpiringSoon(0, TODAY, WARN)).isTrue();
    }

    @Test
    void itemWhoseNearestLotExpiresTheDayAfterTheWarnDateIsNot() {
        assertThat(InventoryQueryRepository.itemExpiringSoon(0, WARN.plusDays(1), WARN)).isFalse();
    }

    @Test
    void itemWithNoExpiredStockAndNoDatedLotIsNot() {
        assertThat(InventoryQueryRepository.itemExpiringSoon(0, null, WARN)).isFalse();
    }

    // ---------------------------------------------------------------- lô

    @Test
    void lotExpiringOnTheWarnDateOrBeforeIsFlaggedIncludingAnAlreadyExpiredLot() {
        assertThat(InventoryQueryRepository.lotExpiringSoon(WARN, WARN)).isTrue();
        assertThat(InventoryQueryRepository.lotExpiringSoon(TODAY.plusDays(10), WARN)).isTrue();
        assertThat(InventoryQueryRepository.lotExpiringSoon(TODAY.minusDays(3), WARN)).isTrue();
    }

    @Test
    void lotExpiringAfterTheWarnDateIsNot() {
        assertThat(InventoryQueryRepository.lotExpiringSoon(WARN.plusDays(1), WARN)).isFalse();
        assertThat(InventoryQueryRepository.lotExpiringSoon(TODAY.plusDays(200), WARN)).isFalse();
    }

    @Test
    void lotWithoutExpiryDateIsNeverFlaggedAndDoesNotThrow() {
        assertThat(InventoryQueryRepository.lotExpiringSoon(null, WARN)).isFalse();
    }

    // ---------------------------------------------------------------- mapper đọc đúng cột

    private static ResultSet itemRow(int expired, LocalDate nearest) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("product_type")).thenReturn("DRUG");
        when(rs.getInt("expired")).thenReturn(expired);
        when(rs.getDate("nearest")).thenReturn(nearest == null ? null : Date.valueOf(nearest));
        return rs;
    }

    @Test
    void itemMapperFlagsFromTheExpiredAndNearestColumns() throws SQLException {
        InventoryItemResponse expired = InventoryQueryRepository.mapItem(itemRow(4, null), WARN);
        InventoryItemResponse onBoundary = InventoryQueryRepository.mapItem(itemRow(0, WARN), WARN);
        InventoryItemResponse far = InventoryQueryRepository.mapItem(itemRow(0, WARN.plusDays(1)), WARN);
        InventoryItemResponse empty = InventoryQueryRepository.mapItem(itemRow(0, null), WARN);

        assertThat(expired.expiringSoon()).isTrue();
        assertThat(expired.expiredQuantity()).isEqualTo(4);
        assertThat(onBoundary.expiringSoon()).isTrue();
        assertThat(onBoundary.nearestExpiryDate()).isEqualTo(WARN);
        assertThat(far.expiringSoon()).isFalse();
        assertThat(empty.expiringSoon()).isFalse();
        assertThat(empty.nearestExpiryDate()).isNull();
    }

    private static ResultSet lotRow(LocalDate expiry) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getDate("expiry_date")).thenReturn(expiry == null ? null : Date.valueOf(expiry));
        return rs;
    }

    @Test
    void lotMapperFlagsFromTheExpiryDateColumn() throws SQLException {
        StockLotResponse soon = InventoryQueryRepository.mapLot(lotRow(TODAY.plusDays(10)), WARN);
        StockLotResponse far = InventoryQueryRepository.mapLot(lotRow(TODAY.plusDays(40)), WARN);
        StockLotResponse noExpiry = InventoryQueryRepository.mapLot(lotRow(null), WARN);

        assertThat(soon.expiringSoon()).isTrue();
        assertThat(far.expiringSoon()).isFalse();
        assertThat(noExpiry.expiringSoon()).isFalse();
        assertThat(noExpiry.expiryDate()).isNull();
    }
}
