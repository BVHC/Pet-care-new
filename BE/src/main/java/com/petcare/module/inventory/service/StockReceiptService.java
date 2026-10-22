package com.petcare.module.inventory.service;

import static com.petcare.module.inventory.entity.StockReceiptStatus.CANCELLED;
import static com.petcare.module.inventory.entity.StockReceiptStatus.CONFIRMED;
import static com.petcare.module.inventory.entity.StockReceiptStatus.DRAFT;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.inventory.dto.CancelReceiptRequest;
import com.petcare.module.inventory.dto.DateRange;
import com.petcare.module.inventory.dto.ReceiptLineRequest;
import com.petcare.module.inventory.dto.ReceiptLineResponse;
import com.petcare.module.inventory.dto.StockReceiptRequest;
import com.petcare.module.inventory.dto.StockReceiptResponse;
import com.petcare.module.inventory.dto.StockReceiptSummaryResponse;
import com.petcare.module.inventory.entity.LockedLot;
import com.petcare.module.inventory.entity.MovementType;
import com.petcare.module.inventory.entity.StockReceipt;
import com.petcare.module.inventory.entity.StockReceiptLine;
import com.petcare.module.inventory.entity.StockReceiptStatus;
import com.petcare.module.inventory.entity.StockSources;
import com.petcare.module.inventory.entity.Supplier;
import com.petcare.module.inventory.fsm.StockReceiptTransitionHandler;
import com.petcare.module.inventory.mapper.StockReceiptMapper;
import com.petcare.module.inventory.repository.StockLotDao;
import com.petcare.module.inventory.repository.StockReceiptQueryRepository;
import com.petcare.module.inventory.repository.StockReceiptRepository;
import com.petcare.module.inventory.repository.SupplierRepository;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.sequence.DocumentCodeGenerator;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

/**
 * UC74 — phiếu nhập kho (inventory-v1 #5–10; BR-KO-02, 03, 04; FSM 03 #9; docs/adr/0042, 0043). Mỗi use case một
 * {@code @Transactional}, không {@code noRollbackFor}. Làm việc trên chi nhánh của người gọi: phiếu chi nhánh khác → 403
 * ({@code BranchScope.check}), không có → 404.
 * <p>
 * Thứ tự khóa: <b>phiếu</b> ({@code FOR NO KEY UPDATE}, trước mọi lần đọc entity) → nhà cung cấp {@code FOR SHARE} →
 * sản phẩm {@code FOR SHARE} (qua {@code CatalogQueryApi.findProductForShare}) → dòng tồn / lô (tạo bằng
 * {@code ON CONFLICT DO NOTHING}, theo thứ tự cố định) → khóa mọi lô một câu id tăng dần → {@link StockLedger}.
 * <ul>
 *   <li>Tạo / sửa nháp: nhà cung cấp phải đang hợp tác (BR-KO-02), sản phẩm phải tồn tại; số lô / hạn dùng chưa bắt buộc
 *       ("sửa tự do"). Mã phiếu lấy <b>sau cùng</b> để giữ khóa bộ đếm ngắn.</li>
 *   <li>Xác nhận: mọi lỗi dòng gom vào một message BR-KO-03; nhiều dòng cùng sản phẩm + số lô + hạn vào cùng một lô.</li>
 *   <li>Hủy phiếu đã xác nhận: gom số lượng theo lô, lô nào không đủ → 400 BR-KO-04 nêu đủ; ghi audit.</li>
 * </ul>
 */
@Service
public class StockReceiptService {

    static final int MAX_PAGE_SIZE = 100;
    private static final String DOC_TYPE = "stock_receipts";
    private static final String PREFIX = "PN";

    private final StockReceiptRepository receipts;
    private final SupplierRepository suppliers;
    private final StockReceiptQueryRepository queries;
    private final StockLotDao lots;
    private final StockLedger ledger;
    private final CatalogQueryApi catalog;
    private final DocumentCodeGenerator codes;
    private final StockReceiptTransitionHandler fsm;
    private final StockReceiptMapper mapper;
    private final BranchScope branchScope;
    private final AuditRecorder audit;
    private final Validator validator;
    private final Clock clock;

    public StockReceiptService(StockReceiptRepository receipts, SupplierRepository suppliers,
            StockReceiptQueryRepository queries, StockLotDao lots, StockLedger ledger, CatalogQueryApi catalog,
            DocumentCodeGenerator codes, StockReceiptTransitionHandler fsm, StockReceiptMapper mapper,
            BranchScope branchScope, AuditRecorder audit, Validator validator, Clock clock) {
        this.receipts = receipts;
        this.suppliers = suppliers;
        this.queries = queries;
        this.lots = lots;
        this.ledger = ledger;
        this.catalog = catalog;
        this.codes = codes;
        this.fsm = fsm;
        this.mapper = mapper;
        this.branchScope = branchScope;
        this.audit = audit;
        this.validator = validator;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- đọc

    /** Sắp {@code receipt_date DESC, id DESC}; {@code size} kẹp 1–100, {@code page} âm = 0; {@code from > to} → 400. */
    @Transactional(readOnly = true)
    public PageResponse<StockReceiptSummaryResponse> listReceipts(StockReceiptStatus status, LocalDate from,
            LocalDate to, Long supplierId, int page, int size) {
        Set<ConstraintViolation<DateRange>> violations = validator.validate(new DateRange(from, to));
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        StockReceiptQueryRepository.Filter filter = new StockReceiptQueryRepository.Filter(branchId(), status, from,
                to, supplierId);
        long total = queries.count(filter);
        return PageResponse.of(new PageImpl<>(queries.findPage(filter, pageable.getOffset(), pageable.getPageSize()),
                pageable, total));
    }

    @Transactional(readOnly = true)
    public StockReceiptResponse getReceipt(Long receiptId) {
        StockReceipt receipt = receipts.findById(receiptId).orElseThrow(() -> notFound(receiptId));
        branchScope.check(receipt.getBranchId());
        return view(receipt);
    }

    // ---------------------------------------------------------------- tạo, sửa nháp

    @Transactional
    public StockReceiptResponse createReceipt(StockReceiptRequest request) {
        long branchId = branchId();
        requireActiveSupplier(request.supplierId());
        requireProductsExist(request.lines());
        fsm.validateInitial(DRAFT);
        String code = codes.next(DOC_TYPE, PREFIX, LocalDate.now(clock));
        StockReceipt saved = receipts.saveAndFlush(StockReceipt.draft(code, branchId, request.supplierId(),
                request.receiptDate(), Values.optional(request.note(), null), branchScope.current().accountId(),
                toLines(request.lines())));
        return view(saved);
    }

    @Transactional
    public StockReceiptResponse updateReceipt(Long receiptId, StockReceiptRequest request) {
        StockReceipt receipt = lockOwned(receiptId);
        if (receipt.getStatus() != DRAFT) {
            throw new InvalidStateTransitionException("phiếu nhập kho (chỉ sửa được khi nháp)",
                    receipt.getStatus().name(), DRAFT.name());
        }
        requireActiveSupplier(request.supplierId());
        requireProductsExist(request.lines());
        receipt.replaceDraft(request.supplierId(), request.receiptDate(), Values.optional(request.note(), null),
                toLines(request.lines()));
        receipts.flush();
        return view(receipt);
    }

    // ---------------------------------------------------------------- xác nhận

    @Transactional
    public StockReceiptResponse confirmReceipt(Long receiptId) {
        StockReceipt receipt = lockOwned(receiptId);
        fsm.validateTransition(receipt.getStatus(), CONFIRMED);
        Supplier supplier = suppliers.findByIdForShare(receipt.getSupplierId())
                .orElseThrow(() -> new ResourceNotFoundException("nhà cung cấp", receipt.getSupplierId()));
        if (!supplier.isActive()) {
            throw new BusinessRuleViolationException("BR-KO-02",
                    "Nhà cung cấp " + supplier.getName() + " đã ngừng hợp tác");
        }
        LocalDate today = LocalDate.now(clock);
        List<StockReceiptLine> lines = receipt.getLines();
        Map<Long, ProductInfo> productsById = new LinkedHashMap<>();
        for (Long productId : new TreeSet<>(lines.stream().map(StockReceiptLine::getProductId).toList())) {
            productsById.put(productId, catalog.findProductForShare(productId)
                    .orElseThrow(() -> new ResourceNotFoundException("sản phẩm", productId)));
        }
        List<String> errors = validateForConfirm(receipt, lines, productsById, today);
        if (!errors.isEmpty()) {
            throw new BusinessRuleViolationException("BR-KO-03", String.join("; ", errors));
        }

        long actor = branchScope.current().accountId();
        Map<Long, Long> lotIdByLine = ensureLots(receipt.getBranchId(), lines, productsById);
        Map<Long, LockedLot> locked = lockById(lotIdByLine.values());
        Map<Long, Integer> receivedByLot = new TreeMap<>();
        for (StockReceiptLine line : lines) {
            Long lotId = lotIdByLine.get(line.getId());
            receivedByLot.merge(lotId, line.getQuantity(), Math::addExact);
            line.assignLot(lotId);
        }
        receivedByLot.forEach((lotId, quantity) -> ledger.apply(locked.get(lotId), quantity, MovementType.RECEIPT,
                StockSources.STOCK_RECEIPTS, receipt.getId(), actor));
        receipt.confirm(actor, Instant.now(clock));
        receipts.flush();
        return view(receipt);
    }

    // ---------------------------------------------------------------- hủy

    @Transactional
    public StockReceiptResponse cancelReceipt(Long receiptId, CancelReceiptRequest request) {
        StockReceipt receipt = lockOwned(receiptId);
        StockReceiptStatus before = receipt.getStatus();
        fsm.validateTransition(before, CANCELLED);
        String reason = request == null ? null : Values.optional(request.reason(), null);
        long actor = branchScope.current().accountId();

        if (before == CONFIRMED) {
            Map<Long, Integer> toRemove = new TreeMap<>();
            receipt.getLines().forEach(line -> toRemove.merge(line.getStockLotId(), line.getQuantity(), Math::addExact));
            Map<Long, LockedLot> locked = lockById(toRemove.keySet());
            List<String> lacking = new ArrayList<>();
            toRemove.forEach((lotId, quantity) -> {
                LockedLot lot = locked.get(lotId);
                if (lot.quantity() < quantity) {
                    lacking.add("lô " + StockLedger.describe(lot) + " còn " + lot.quantity() + ", cần trừ " + quantity);
                }
            });
            if (!lacking.isEmpty()) {
                throw new BusinessRuleViolationException("BR-KO-04", "Không hủy được phiếu vì tồn không đủ để trừ lại: "
                        + String.join("; ", lacking) + ". Hãy lập phiếu điều chỉnh tồn (BR-KO-06)");
            }
            toRemove.forEach((lotId, quantity) -> ledger.apply(locked.get(lotId), -quantity,
                    MovementType.RECEIPT_CANCEL, StockSources.STOCK_RECEIPTS, receipt.getId(), actor));
        }
        receipt.cancel(actor, Instant.now(clock), reason);
        receipts.flush();
        if (before == CONFIRMED) {
            audit.record(AuditEntry.of(InventoryAuditActions.STOCK_RECEIPT_CANCELLED)
                    .entity("stock_receipts", receipt.getId())
                    .before(Map.of("status", before.name())).after(Map.of("status", CANCELLED.name()))
                    .reason(reason));
        }
        return view(receipt);
    }

    // ---------------------------------------------------------------- nội bộ

    private long branchId() {
        Long branchId = branchScope.current().branchId();
        if (branchId == null) {
            throw new IllegalStateException("Branch manager without a branch");
        }
        return branchId;
    }

    /** Khóa phiếu rồi mới đọc entity; phiếu chi nhánh khác → 403. */
    private StockReceipt lockOwned(Long receiptId) {
        StockReceipt receipt = receipts.findByIdForUpdate(receiptId).orElseThrow(() -> notFound(receiptId));
        branchScope.check(receipt.getBranchId());
        return receipt;
    }

    private static ResourceNotFoundException notFound(Long receiptId) {
        return new ResourceNotFoundException("phiếu nhập kho", receiptId);
    }

    private void requireActiveSupplier(Long supplierId) {
        Supplier supplier = suppliers.findByIdForShare(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("nhà cung cấp", supplierId));
        if (!supplier.isActive()) {
            throw new BusinessRuleViolationException("BR-KO-02",
                    "Nhà cung cấp " + supplier.getName() + " đã ngừng hợp tác, không chọn được ở phiếu nhập mới");
        }
    }

    private void requireProductsExist(List<ReceiptLineRequest> lines) {
        List<Long> ids = lines.stream().map(ReceiptLineRequest::productId).distinct().toList();
        Set<Long> found = catalog.findProducts(ids).stream().map(ProductInfo::productId).collect(Collectors.toSet());
        for (Long id : ids) {
            if (!found.contains(id)) {
                throw new ResourceNotFoundException("sản phẩm", id);
            }
        }
    }

    private static List<StockReceiptLine> toLines(List<ReceiptLineRequest> requests) {
        return requests.stream().map(r -> StockReceiptLine.of(r.productId(), r.quantity(), r.unitCost(),
                Values.optional(r.lotNumber(), null), r.expiryDate())).collect(Collectors.toCollection(ArrayList::new));
    }

    /** BR-KO-03: ngày nhập, số lô + hạn dùng với sản phẩm quản lý hạn dùng; sản phẩm khác không được có số lô / hạn. */
    private static List<String> validateForConfirm(StockReceipt receipt, List<StockReceiptLine> lines,
            Map<Long, ProductInfo> products, LocalDate today) {
        List<String> errors = new ArrayList<>();
        if (receipt.getReceiptDate().isAfter(today)) {
            errors.add("ngày nhập không được ở tương lai");
        }
        int index = 0;
        for (StockReceiptLine line : lines) {
            index++;
            ProductInfo product = products.get(line.getProductId());
            String label = "dòng " + index + " (" + product.name() + ")";
            if (product.tracksExpiry()) {
                if (line.getLotNumber() == null) {
                    errors.add(label + " thiếu số lô");
                }
                if (line.getExpiryDate() == null) {
                    errors.add(label + " thiếu hạn dùng");
                } else if (!line.getExpiryDate().isAfter(receipt.getReceiptDate())) {
                    errors.add(label + " có hạn dùng không sau ngày nhập");
                }
            } else if (line.getLotNumber() != null || line.getExpiryDate() != null) {
                errors.add(label + " không quản lý hạn dùng nên không có số lô / hạn dùng");
            }
        }
        return errors;
    }

    /**
     * Tạo dòng tồn và lô theo thứ tự cố định (sản phẩm, số lô, hạn tăng dần) để hai phiếu cùng chèn không chờ nhau vòng
     * tròn; trả {@code lineId → lotId}.
     */
    private Map<Long, Long> ensureLots(long branchId, List<StockReceiptLine> lines, Map<Long, ProductInfo> products) {
        List<StockReceiptLine> ordered = lines.stream().sorted(Comparator
                .comparing(StockReceiptLine::getProductId)
                .thenComparing(l -> l.getLotNumber() == null ? "" : l.getLotNumber())
                .thenComparing(l -> l.getExpiryDate() == null ? LocalDate.MIN : l.getExpiryDate())).toList();
        Map<Long, Long> itemByProduct = new TreeMap<>();
        Map<Long, Long> lotByLine = new LinkedHashMap<>();
        for (StockReceiptLine line : ordered) {
            long item = itemByProduct.computeIfAbsent(line.getProductId(), p -> lots.ensureItem(branchId, p));
            long lot = products.get(line.getProductId()).tracksExpiry()
                    ? lots.ensureLot(item, line.getLotNumber(), line.getExpiryDate())
                    : lots.ensureDefaultLot(item);
            lotByLine.put(line.getId(), lot);
        }
        return lotByLine;
    }

    private Map<Long, LockedLot> lockById(Collection<Long> lotIds) {
        return lots.lockLots(new TreeSet<>(lotIds)).stream()
                .collect(Collectors.toMap(LockedLot::id, Function.identity(), (a, b) -> a, TreeMap::new));
    }

    private StockReceiptResponse view(StockReceipt receipt) {
        String supplierName = suppliers.findById(receipt.getSupplierId()).map(Supplier::getName).orElse(null);
        Map<Long, String> names = catalog.findProducts(
                receipt.getLines().stream().map(StockReceiptLine::getProductId).distinct().toList()).stream()
                .collect(Collectors.toMap(ProductInfo::productId, ProductInfo::name));
        List<ReceiptLineResponse> lines = receipt.getLines().stream()
                .map(line -> mapper.toLine(line, names.get(line.getProductId()))).toList();
        return mapper.toResponse(receipt, supplierName, lines);
    }
}
