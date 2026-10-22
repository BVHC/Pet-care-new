package com.petcare.module.inventory.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.inventory.dto.AdjustmentLineRequest;
import com.petcare.module.inventory.dto.AdjustmentLineResponse;
import com.petcare.module.inventory.dto.DateRange;
import com.petcare.module.inventory.dto.StockAdjustmentRequest;
import com.petcare.module.inventory.dto.StockAdjustmentResponse;
import com.petcare.module.inventory.entity.LockedLot;
import com.petcare.module.inventory.entity.MovementType;
import com.petcare.module.inventory.entity.StockAdjustment;
import com.petcare.module.inventory.entity.StockAdjustmentLine;
import com.petcare.module.inventory.entity.StockAdjustmentReason;
import com.petcare.module.inventory.entity.StockSources;
import com.petcare.module.inventory.mapper.StockAdjustmentMapper;
import com.petcare.module.inventory.repository.StockAdjustmentLookupRepository;
import com.petcare.module.inventory.repository.StockAdjustmentLookupRepository.LotInfo;
import com.petcare.module.inventory.repository.StockAdjustmentRepository;
import com.petcare.module.inventory.repository.StockLotDao;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.sequence.DocumentCodeGenerator;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

/**
 * UC76 — điều chỉnh tồn (inventory-v1 #15–17; BR-KO-01, 06; BR-QT-15; docs/adr/0042, 0043). Một
 * {@code @Transactional} cho cả phiếu, không {@code noRollbackFor}. Hiệu lực ngay khi lưu, bất biến sau đó.
 * <ol>
 *   <li>Kiểm hình thức ngoài DB: {@code OTHER} thiếu ghi chú → 400 BR-KO-06.</li>
 *   <li>Khóa <b>mọi lô một câu, id tăng dần</b>, chỉ lô thuộc chi nhánh của người gọi (lô chi nhánh khác coi như không có →
 *       404, không lộ). Đọc số dư từ chính câu khóa.</li>
 *   <li>Giảm quá số dư của lô → 400 BR-KO-06 nêu đủ các lô (kiểm trước mọi lệnh ghi).</li>
 *   <li>Mã phiếu {@code DC-yyMM-nnnnn} lấy sau cùng → lưu phiếu → {@code StockLedger.apply} từng lô (movement
 *       {@code ADJUSTMENT}) → audit {@code STOCK_ADJUSTED}.</li>
 * </ol>
 * Lô đã hết hạn điều chỉnh được (lý do hết hạn là trường hợp chính).
 */
@Service
public class StockAdjustmentService {

    static final int MAX_PAGE_SIZE = 100;
    private static final String DOC_TYPE = "stock_adjustments";
    private static final String PREFIX = "DC";

    private final StockAdjustmentRepository adjustments;
    private final StockAdjustmentLookupRepository lookup;
    private final StockLotDao lots;
    private final StockLedger ledger;
    private final CatalogQueryApi catalog;
    private final DocumentCodeGenerator codes;
    private final StockAdjustmentMapper mapper;
    private final BranchScope branchScope;
    private final AuditRecorder audit;
    private final Validator validator;
    private final Clock clock;

    public StockAdjustmentService(StockAdjustmentRepository adjustments, StockAdjustmentLookupRepository lookup,
            StockLotDao lots, StockLedger ledger, CatalogQueryApi catalog, DocumentCodeGenerator codes,
            StockAdjustmentMapper mapper, BranchScope branchScope, AuditRecorder audit, Validator validator,
            Clock clock) {
        this.adjustments = adjustments;
        this.lookup = lookup;
        this.lots = lots;
        this.ledger = ledger;
        this.catalog = catalog;
        this.codes = codes;
        this.mapper = mapper;
        this.branchScope = branchScope;
        this.audit = audit;
        this.validator = validator;
        this.clock = clock;
    }

    @Transactional
    public StockAdjustmentResponse createAdjustment(StockAdjustmentRequest request) {
        long branchId = branchId();
        String note = Values.optional(request.note(), null);
        if (request.reason() == StockAdjustmentReason.OTHER && note == null) {
            throw new BusinessRuleViolationException("BR-KO-06", "Lý do \"Khác\" bắt buộc có ghi chú");
        }

        Map<Long, Integer> deltaByLot = new TreeMap<>();
        request.lines().forEach(line -> deltaByLot.put(line.stockLotId(), line.quantityDelta()));
        Map<Long, LockedLot> locked = lots.lockLotsInBranch(deltaByLot.keySet(), branchId).stream()
                .collect(Collectors.toMap(LockedLot::id, Function.identity(), (a, b) -> a, TreeMap::new));
        for (Long lotId : deltaByLot.keySet()) {
            if (!locked.containsKey(lotId)) {
                throw new ResourceNotFoundException("lô hàng", lotId);
            }
        }
        List<String> lacking = new ArrayList<>();
        deltaByLot.forEach((lotId, delta) -> {
            LockedLot lot = locked.get(lotId);
            if (delta < 0 && lot.quantity() + delta < 0) {
                lacking.add("lô " + StockLedger.describe(lot) + " còn " + lot.quantity() + ", giảm " + (-delta));
            }
        });
        if (!lacking.isEmpty()) {
            throw new BusinessRuleViolationException("BR-KO-06",
                    "Không giảm quá tồn của lô: " + String.join("; ", lacking));
        }

        long actor = branchScope.current().accountId();
        List<StockAdjustmentLine> lines = request.lines().stream()
                .map(l -> StockAdjustmentLine.of(l.stockLotId(), l.quantityDelta())).toList();
        String code = codes.next(DOC_TYPE, PREFIX, LocalDate.now(clock));
        StockAdjustment saved = adjustments.saveAndFlush(
                StockAdjustment.create(code, branchId, request.reason(), note, actor, lines));

        Map<Long, Integer> balanceByLot = new LinkedHashMap<>();
        deltaByLot.forEach((lotId, delta) -> balanceByLot.put(lotId, ledger.apply(locked.get(lotId), delta,
                MovementType.ADJUSTMENT, StockSources.STOCK_ADJUSTMENTS, saved.getId(), actor).quantity()));

        List<Map<String, Object>> auditLines = request.lines().stream().map(l -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("stockLotId", l.stockLotId());
            entry.put("quantityDelta", l.quantityDelta());
            entry.put("balanceAfter", balanceByLot.get(l.stockLotId()));
            return entry;
        }).collect(Collectors.toList());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("reason", request.reason().name());
        snapshot.put("lines", auditLines);
        audit.record(AuditEntry.of(InventoryAuditActions.STOCK_ADJUSTED).entity("stock_adjustments", saved.getId())
                .after(snapshot).reason(note));

        return views(List.of(saved), Map.of(saved.getId(), balanceByLot)).get(0);
    }

    @Transactional(readOnly = true)
    public PageResponse<StockAdjustmentResponse> listAdjustments(LocalDate from, LocalDate to, int page, int size) {
        Set<ConstraintViolation<DateRange>> violations = validator.validate(new DateRange(from, to));
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        long branchId = branchId();
        java.time.ZoneId zone = clock.getZone();
        Specification<StockAdjustment> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("branchId"), branchId));
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<java.time.Instant>get("createdAt"),
                        from.atStartOfDay(zone).toInstant()));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.<java.time.Instant>get("createdAt"),
                        to.plusDays(1).atStartOfDay(zone).toInstant()));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        Page<StockAdjustment> found = adjustments.findAll(spec, pageable);
        Map<Long, Map<Long, Integer>> balances = lookup.balancesAfter(found.getContent().stream()
                .map(StockAdjustment::getId).toList());
        List<StockAdjustmentResponse> content = views(found.getContent(), balances);
        return PageResponse.of(new org.springframework.data.domain.PageImpl<>(content, pageable,
                found.getTotalElements()));
    }

    @Transactional(readOnly = true)
    public StockAdjustmentResponse getAdjustment(Long adjustmentId) {
        StockAdjustment adjustment = adjustments.findById(adjustmentId)
                .orElseThrow(() -> new ResourceNotFoundException("phiếu điều chỉnh tồn", adjustmentId));
        branchScope.check(adjustment.getBranchId());
        return views(List.of(adjustment), lookup.balancesAfter(List.of(adjustmentId))).get(0);
    }

    // ---------------------------------------------------------------- nội bộ

    private long branchId() {
        Long branchId = branchScope.current().branchId();
        if (branchId == null) {
            throw new IllegalStateException("Branch manager without a branch");
        }
        return branchId;
    }

    /** Dựng phản hồi cho nhiều phiếu bằng ba câu tra cứu (lô, sản phẩm, số dư sau) — không N+1. */
    private List<StockAdjustmentResponse> views(List<StockAdjustment> list,
            Map<Long, Map<Long, Integer>> balances) {
        Set<Long> lotIds = new HashSet<>();
        list.forEach(a -> a.getLines().forEach(l -> lotIds.add(l.getStockLotId())));
        Map<Long, LotInfo> lotInfo = lookup.lotInfo(lotIds);
        Collection<Long> productIds = lotInfo.values().stream().map(LotInfo::productId).collect(Collectors.toSet());
        Map<Long, String> names = catalog.findProducts(new TreeSet<>(productIds)).stream()
                .collect(Collectors.toMap(ProductInfo::productId, ProductInfo::name));
        return list.stream().map(a -> {
            List<AdjustmentLineResponse> lines = a.getLines().stream().map(l -> {
                LotInfo info = lotInfo.get(l.getStockLotId());
                int balance = balances.getOrDefault(a.getId(), Map.of()).getOrDefault(l.getStockLotId(), 0);
                return mapper.toLine(l, info.productId(), names.get(info.productId()), info.lotNumber(), balance);
            }).toList();
            return mapper.toResponse(a, lines);
        }).toList();
    }
}
