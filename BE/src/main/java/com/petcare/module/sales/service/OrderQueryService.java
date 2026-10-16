package com.petcare.module.sales.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.repository.SalesOrderRepository;
import com.petcare.module.visit.api.VisitQueryApi;

import lombok.RequiredArgsConstructor;

/**
 * {@link OrderQueryApi} thật (T19). Đọc thuần, không khóa, chạy trong transaction của bên gọi (customer khóa
 * {@code customers → pets} trước, khóa tài khoản của identity). Thay {@code OrderQueryApiPlaceholder} do BE-1 tạo.
 */
@Service
@RequiredArgsConstructor
public class OrderQueryService implements OrderQueryApi {

    private final SalesOrderRepository orders;
    private final VisitQueryApi visits;
    private final BoardingQueryApi boardings;

    @Override
    public boolean existsByCustomer(Long customerId) {
        return orders.existsByCustomerId(customerId);
    }

    /** Order nguồn VISIT / BOARDING luôn kèm Visit / đặt chỗ của thú (06 §4); danh sách rỗng thì không có Order. */
    @Override
    public boolean hasPendingOrderForPet(Long petId) {
        List<Long> visitIds = visits.findVisitIdsByPet(petId);
        if (!visitIds.isEmpty() && orders.existsByStatusAndVisitIdIn(OrderStatus.PENDING, visitIds)) {
            return true;
        }
        List<Long> bookingIds = boardings.findBookingIdsByPet(petId);
        return !bookingIds.isEmpty() && orders.existsByStatusAndBoardingBookingIdIn(OrderStatus.PENDING, bookingIds);
    }

    @Override
    public List<UnpaidOrderRef> findUnpaidOrdersByCustomer(Long customerId) {
        return orders.findIdAndBranchByCustomer(customerId, List.of(OrderStatus.OPEN, OrderStatus.PENDING)).stream()
                .map(row -> new UnpaidOrderRef(row.getId(), row.getBranchId()))
                .toList();
    }
}
