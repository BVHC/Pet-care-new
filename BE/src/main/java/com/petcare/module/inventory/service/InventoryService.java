package com.petcare.module.inventory.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.inventory.dto.InventoryItemResponse;
import com.petcare.module.inventory.dto.StockLotResponse;
import com.petcare.module.inventory.repository.InventoryQueryRepository;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;

/**
 * UC75 — xem tồn và đặt tồn tối thiểu (inventory-v1 #11–14; BR-KO-01, 05, 07; docs/adr/0042). Luôn làm trên chi nhánh
 * của người gọi (A05–A08 đều có chi nhánh); chỉ BRANCH_MANAGER ghi. Không audit. "Hôm nay" theo bean {@code Clock}
 * (Asia/Ho_Chi_Minh): hết hạn = hạn dùng trước hôm nay.
 */
@Service
public class InventoryService {

    static final int MAX_PAGE_SIZE = 100;

    private final InventoryQueryRepository inventory;
    private final CatalogQueryApi catalog;
    private final BranchScope branchScope;
    private final Clock clock;

    public InventoryService(InventoryQueryRepository inventory, CatalogQueryApi catalog, BranchScope branchScope,
            Clock clock) {
        this.inventory = inventory;
        this.catalog = catalog;
        this.branchScope = branchScope;
        this.clock = clock;
    }

    /** Sắp {@code name, id}; {@code size} kẹp 1–100, {@code page} âm = 0; {@code expiringWithinDays} âm → coi như 0. */
    @Transactional(readOnly = true)
    public PageResponse<InventoryItemResponse> listInventory(String q, ProductType productType, boolean belowMin,
            Integer expiringWithinDays, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        String needle = q == null || q.isBlank() ? null : q.strip().toLowerCase(Locale.ROOT);
        InventoryQueryRepository.Filter filter = new InventoryQueryRepository.Filter(branchId(), today(), needle,
                productType, belowMin, expiringWithinDays == null ? null : Math.max(expiringWithinDays, 0));
        long total = inventory.count(filter);
        return PageResponse.of(new PageImpl<>(inventory.findPage(filter, pageable.getOffset(), pageable.getPageSize()),
                pageable, total));
    }

    @Transactional(readOnly = true)
    public InventoryItemResponse getItem(Long productId) {
        return inventory.findItem(branchId(), productId, today())
                .orElseThrow(() -> new ResourceNotFoundException("sản phẩm", productId));
    }

    /** Lô còn số dư, thứ tự FEFO; sản phẩm không tồn tại → 404, chưa có lô → mảng rỗng. */
    @Transactional(readOnly = true)
    public List<StockLotResponse> listLots(Long productId) {
        requireProduct(productId);
        return inventory.findLots(branchId(), productId, today());
    }

    @Transactional
    public InventoryItemResponse setMinQuantity(Long productId, int minQuantity) {
        requireProduct(productId);
        inventory.setMinQuantity(branchId(), productId, minQuantity, Instant.now(clock));
        return getItem(productId);
    }

    private void requireProduct(Long productId) {
        if (catalog.findProduct(productId).isEmpty()) {
            throw new ResourceNotFoundException("sản phẩm", productId);
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private long branchId() {
        Long branchId = branchScope.current().branchId();
        if (branchId == null) {
            throw new IllegalStateException("Inventory staff without a branch");
        }
        return branchId;
    }
}
