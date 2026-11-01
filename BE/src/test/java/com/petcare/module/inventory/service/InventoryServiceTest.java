package com.petcare.module.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.inventory.dto.InventoryItemResponse;
import com.petcare.module.inventory.repository.InventoryQueryRepository;
import com.petcare.module.inventory.repository.InventoryQueryRepository.Filter;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Mốc cờ {@code expiringSoon} (docs/adr/0088): [CFG] {@code stock.expiry_warning_days} đọc đúng một lần mỗi request,
 * {@code warnDate = hôm nay + N} từ cùng {@code today} với bộ lọc, mọi đường (danh sách, một sản phẩm, lô, đặt tồn tối
 * thiểu) dùng cùng quy tắc. Cố ý stub N = 45 (không phải mặc định 30) để một hằng số trong code không lọt qua.
 */
class InventoryServiceTest {

    private static final long BRANCH = 5L;
    private static final long PRODUCT = 12L;
    private static final int N = 45;
    /** 2026-10-30 07:00 giờ Việt Nam. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"),
            ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);

    private final InventoryQueryRepository inventory = mock(InventoryQueryRepository.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final BranchScope scope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final InventoryService service = new InventoryService(inventory, catalog, scope, CLOCK, configs);

    private final InventoryItemResponse item = new InventoryItemResponse(PRODUCT, "Thuốc", "SKU", "hộp",
            ProductType.DRUG, true, 5, 5, 0, false, null, 0, false);

    @BeforeEach
    void setUp() {
        when(scope.current()).thenReturn(principal);
        when(principal.branchId()).thenReturn(BRANCH);
        when(configs.getInt(ConfigKey.STOCK_EXPIRY_WARNING_DAYS)).thenReturn(N);
        when(catalog.findProduct(PRODUCT)).thenReturn(Optional.of(new ProductInfo(PRODUCT, "Thuốc", ProductType.DRUG,
                false, true, null, 100_000L, true)));
        when(inventory.findItem(eq(BRANCH), eq(PRODUCT), any(), any())).thenReturn(Optional.of(item));
    }

    @Test
    void listReadsTheConfigOnceAndPassesTodayPlusNAlongsideTheFilterToday() {
        when(inventory.count(any())).thenReturn(0L);
        when(inventory.findPage(any(), any(), anyLong(), anyInt())).thenReturn(List.of());

        service.listInventory(null, null, false, null, 0, 20);

        ArgumentCaptor<Filter> filter = ArgumentCaptor.forClass(Filter.class);
        ArgumentCaptor<LocalDate> warn = ArgumentCaptor.forClass(LocalDate.class);
        verify(inventory).findPage(filter.capture(), warn.capture(), eq(0L), eq(20));
        assertThat(filter.getValue().today()).isEqualTo(TODAY);
        assertThat(warn.getValue()).isEqualTo(TODAY.plusDays(N));
        verify(configs, times(1)).getInt(ConfigKey.STOCK_EXPIRY_WARNING_DAYS);
    }

    @Test
    void getItemReadsTheConfigOnceAndPassesTodayPlusN() {
        assertThat(service.getItem(PRODUCT)).isSameAs(item);

        verify(inventory).findItem(BRANCH, PRODUCT, TODAY, TODAY.plusDays(N));
        verify(configs, times(1)).getInt(ConfigKey.STOCK_EXPIRY_WARNING_DAYS);
    }

    @Test
    void listLotsReadsTheConfigOnceAndPassesTodayPlusN() {
        when(inventory.findLots(eq(BRANCH), eq(PRODUCT), any(), any())).thenReturn(List.of());

        service.listLots(PRODUCT);

        verify(inventory).findLots(BRANCH, PRODUCT, TODAY, TODAY.plusDays(N));
        verify(configs, times(1)).getInt(ConfigKey.STOCK_EXPIRY_WARNING_DAYS);
    }

    @Test
    void setMinQuantityAnswersWithTheSameFlagRuleAsGetItem() {
        assertThat(service.setMinQuantity(PRODUCT, 7)).isSameAs(item);

        verify(inventory).setMinQuantity(eq(BRANCH), eq(PRODUCT), eq(7), any(Instant.class));
        verify(inventory).findItem(BRANCH, PRODUCT, TODAY, TODAY.plusDays(N));
        verify(configs, times(1)).getInt(ConfigKey.STOCK_EXPIRY_WARNING_DAYS);
    }

    @Test
    void aChangedConfigAppliesToTheNextRequestBecauseTheServiceKeepsNoCopy() {
        service.getItem(PRODUCT);
        doReturn(7).when(configs).getInt(ConfigKey.STOCK_EXPIRY_WARNING_DAYS);
        service.getItem(PRODUCT);

        verify(inventory).findItem(BRANCH, PRODUCT, TODAY, TODAY.plusDays(N));
        verify(inventory).findItem(BRANCH, PRODUCT, TODAY, TODAY.plusDays(7));
    }
}
