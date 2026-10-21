package com.petcare.module.inventory.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.inventory.api.StockApi;
import com.petcare.module.inventory.entity.LockedLot;
import com.petcare.module.inventory.entity.MovementType;
import com.petcare.module.inventory.entity.StockSources;
import com.petcare.module.inventory.repository.StockLotDao;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

/**
 * {@link StockApi} thật: xuất FEFO cho thu ngân (Order#8) và tiêm, hoàn lô khi xóa mũi tiêm (docs/adr/0042, BR-KO-01,
 * 05; 06 §5). Mọi method {@code MANDATORY}: chạy trong transaction của sales / visit, bên đó khóa chứng từ của mình
 * (Order, Visit) <b>trước</b> khi gọi vào đây.
 * <ul>
 *   <li>Khóa <b>mọi lô ứng viên bằng một câu, id tăng dần</b>, rồi kiểm tồn dưới khóa (không {@code LIMIT 1 … FOR
 *       UPDATE}); chia FEFO bằng {@link FefoAllocator}; mọi thay đổi số dư qua {@link StockLedger}.</li>
 *   <li>{@code issueForSale} <b>không ném</b> khi thiếu: trả danh sách thiếu và không trừ gì (06 §1). Lô hết hạn không
 *       tính vào tồn khả dụng và không bao giờ bị xuất.</li>
 *   <li>Đầu vào sai (số lượng ≤ 0, thiếu {@code actorId}) là lỗi lập trình của bên gọi → {@code IllegalArgumentException}.</li>
 * </ul>
 */
@Service
public class StockIssueService implements StockApi {

    private final StockLotDao lots;
    private final StockLedger ledger;
    private final Clock clock;

    public StockIssueService(StockLotDao lots, StockLedger ledger, Clock clock) {
        this.lots = lots;
        this.ledger = ledger;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Shortage> issueForPayment(Long branchId, List<OrderSale> orders, Long actorId) {
        require(branchId, "branchId");
        require(actorId, "actorId");
        Map<Long, Integer> requested = new TreeMap<>();
        for (OrderSale order : orders) {
            require(order.orderId(), "orderId");
            for (SaleLine line : order.lines()) {
                if (line.quantity() <= 0) {
                    throw new IllegalArgumentException("Sale quantity must be positive");
                }
                requested.merge(line.productId(), line.quantity(), StockIssueService::addQuantities);
            }
        }
        if (requested.isEmpty()) {
            return List.of();
        }
        LocalDate today = LocalDate.now(clock);
        // Một câu cho hợp sản phẩm của mọi Order (ORDER BY id): hai phiếu thu gộp chéo sản phẩm không khóa lô ngược thứ tự.
        Map<Long, List<LockedLot>> byProduct = lots.lockIssuableLots(branchId, requested.keySet(), today).stream()
                .collect(Collectors.groupingBy(LockedLot::productId));

        List<Shortage> shortages = new ArrayList<>();
        requested.forEach((productId, quantity) -> {
            int available = FefoAllocator.available(byProduct.getOrDefault(productId, List.of()), today);
            if (available < quantity) {
                shortages.add(new Shortage(productId, quantity, available));
            }
        });
        if (!shortages.isEmpty()) {
            return shortages;
        }

        // Chia FEFO lần lượt từng Order (orderId tăng dần) trên cùng tập lô đã khóa; số dư của lô giảm dần sau mỗi lần.
        Map<Long, LockedLot> current = new TreeMap<>();
        byProduct.values().forEach(group -> group.forEach(lot -> current.put(lot.id(), lot)));
        orders.stream().sorted(Comparator.comparing(OrderSale::orderId)).forEach(order -> {
            Map<Long, Integer> perProduct = new TreeMap<>();
            for (SaleLine line : order.lines()) {
                perProduct.merge(line.productId(), line.quantity(), StockIssueService::addQuantities);
            }
            List<FefoAllocator.Take> takes = new ArrayList<>();
            perProduct.forEach((productId, quantity) -> {
                List<LockedLot> candidates = current.values().stream().filter(l -> l.productId().equals(productId))
                        .toList();
                takes.addAll(FefoAllocator.allocate(candidates, quantity, today).orElseThrow(
                        () -> new IllegalStateException("Tồn đã kiểm đủ nhưng không chia được cho sản phẩm "
                                + productId)));
            });
            takes.stream().sorted(Comparator.comparing(t -> t.lot().id())).forEach(take -> {
                ledger.apply(take.lot(), -take.quantity(), MovementType.SALE, StockSources.ORDERS, order.orderId(),
                        actorId);
                LockedLot lot = take.lot();
                current.put(lot.id(), new LockedLot(lot.id(), lot.inventoryItemId(), lot.branchId(), lot.productId(),
                        lot.lotNumber(), lot.expiryDate(), lot.quantity() - take.quantity(), lot.defaultLot()));
            });
        });
        return List.of();
    }

    private static int addQuantities(int a, int b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException overflow) {
            throw new BusinessRuleViolationException("BR-KO-01", "Số lượng yêu cầu vượt giới hạn cho phép");
        }
    }
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Long lockFefoLot(Long branchId, Long productId, int quantity) {
        require(branchId, "branchId");
        require(productId, "productId");
        requirePositive(quantity);
        LocalDate today = LocalDate.now(clock);
        List<LockedLot> candidates = lots.lockIssuableLots(branchId, List.of(productId), today);
        return FefoAllocator.firstLotWith(candidates, quantity, today).map(LockedLot::id)
                .orElseThrow(() -> new BusinessRuleViolationException("BR-KO-05",
                        "Không còn lô chưa hết hạn nào đủ " + quantity + " để xuất (tồn khả dụng "
                                + FefoAllocator.available(candidates, today) + ")"));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void issueForVaccination(Long stockLotId, int quantity, Long vaccinationId, Long actorId) {
        require(stockLotId, "stockLotId");
        require(vaccinationId, "vaccinationId");
        require(actorId, "actorId");
        requirePositive(quantity);
        LockedLot lot = lockOne(stockLotId);
        if (lot.expired(LocalDate.now(clock))) {
            throw new BusinessRuleViolationException("BR-KO-05",
                    "Lô " + StockLedger.describe(lot) + " đã hết hạn, không xuất được");
        }
        ledger.apply(lot, -quantity, MovementType.VACCINATION, StockSources.VACCINATIONS, vaccinationId, actorId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revertVaccination(Long stockLotId, int quantity, Long vaccinationId, Long actorId) {
        require(stockLotId, "stockLotId");
        require(vaccinationId, "vaccinationId");
        require(actorId, "actorId");
        requirePositive(quantity);
        // hoàn đúng lô kể cả khi lô đã hết hạn: số lượng quay về nơi nó đã rời đi (BR-KB-04)
        ledger.apply(lockOne(stockLotId), quantity, MovementType.VACCINATION_REVERT, StockSources.VACCINATIONS,
                vaccinationId, actorId);
    }

    private LockedLot lockOne(Long stockLotId) {
        return lots.lockLots(List.of(stockLotId)).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("lô hàng", stockLotId));
    }

    private static void require(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
    }
}
