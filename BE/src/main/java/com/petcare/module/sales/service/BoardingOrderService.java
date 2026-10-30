package com.petcare.module.sales.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.sales.api.BoardingOrderApi;
import com.petcare.module.sales.entity.OrderLine;
import com.petcare.module.sales.entity.OrderLineType;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.module.sales.fsm.OrderTransitionHandler;
import com.petcare.module.sales.repository.SalesOrderRepository;
import com.petcare.module.sales.repository.SalesOrderRepository.BookingOrderRow;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.sequence.DocumentCodeGenerator;

import lombok.RequiredArgsConstructor;

/**
 * {@link BoardingOrderApi} thật (T43, docs/adr/0086): Order nguồn BOARDING cho lưu trú của boarding. Mọi method
 * {@code MANDATORY} và <b>khóa dòng Order trước khi đọc</b>; boarding đã khóa đặt chỗ trước nên thứ tự là
 * {@code boarding_bookings → orders → document_counters}. Bước kiểm {@code PAID} nằm <b>sau</b> khóa: nếu thu tiền
 * commit trong lúc ta chờ khóa Order {@code PENDING}, câu khóa trả rỗng và nếu không kiểm lại sẽ INSERT Order thứ hai
 * (thu hai lần).
 */
@Service
@RequiredArgsConstructor
public class BoardingOrderService implements BoardingOrderApi {

    static final String DOC_TYPE = "ORDER";
    static final String PREFIX = "DH";
    private static final int BATCH = 100;

    private final SalesOrderRepository orders;
    private final DocumentCodeGenerator codes;
    private final OrderTransitionHandler fsm;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public BoardingOrderRef openOrReuse(BoardingCharge charge) {
        Optional<SalesOrder> pending = orders.findUnfinishedByBookingForUpdate(charge.bookingId());
        if (orders.existsByBoardingBookingIdAndStatus(charge.bookingId(), OrderStatus.PAID)) {
            throw new InvalidStateTransitionException("đơn hàng lưu trú (đã thu tiền, chỉ còn giao thú)",
                    OrderStatus.PAID.name(), OrderStatus.PENDING.name());
        }
        Instant now = Instant.now(clock);
        SalesOrder order;
        if (pending.isPresent()) {
            order = pending.get();
            OrderLine line = boardingLine(order);
            line.repriceBoarding(charge.nights());
            order.recalculateTotal();
        } else {
            fsm.validateInitial(OrderStatus.PENDING);
            order = SalesOrder.openForBoarding(codes.next(DOC_TYPE, PREFIX, LocalDate.now(clock)), charge.branchId(),
                    charge.customerId(), charge.bookingId(), charge.actorId(), now);
            order.addLine(OrderLine.of(OrderLineType.BOARDING, charge.kennelTypeServiceId(), null,
                    charge.description(), charge.nights(), charge.nightlyPrice(), true, charge.actorId(),
                    charge.actorId()));
        }
        SalesOrder saved = orders.saveAndFlush(order);
        return new BoardingOrderRef(saved.getId(), charge.nights(), saved.getTotalAmount());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasPaidOrder(Long bookingId) {
        return orders.existsByBoardingBookingIdAndStatus(bookingId, OrderStatus.PAID);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BoardingOrderRef> findPaidOrder(Long bookingId) {
        return orders.findFirstByBoardingBookingIdAndStatusOrderByIdDesc(bookingId, OrderStatus.PAID)
                .map(o -> new BoardingOrderRef(o.getId(), boardingLine(o).getQuantity(), o.getTotalAmount()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, Long> findActiveOrderIds(Collection<Long> bookingIds) {
        Map<Long, Long> result = new HashMap<>();
        java.util.List<Long> ids = bookingIds.stream().distinct().toList();
        for (int from = 0; from < ids.size(); from += BATCH) {
            for (BookingOrderRow row : orders.findUnfinishedByBookingIds(
                    ids.subList(from, Math.min(ids.size(), from + BATCH)))) {
                result.put(row.getBookingId(), row.getOrderId());
            }
        }
        return result;
    }

    /** Order lưu trú luôn có đúng một dòng BOARDING (do {@link #openOrReuse} tạo); khác là dữ liệu hỏng. */
    private static OrderLine boardingLine(SalesOrder order) {
        return order.getLines().stream().filter(l -> l.getLineType() == OrderLineType.BOARDING).findFirst()
                .orElseThrow(() -> new IllegalStateException("Order lưu trú #" + order.getId() + " không có dòng BOARDING"));
    }
}
