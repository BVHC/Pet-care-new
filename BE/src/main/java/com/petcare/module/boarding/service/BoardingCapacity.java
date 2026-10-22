package com.petcare.module.boarding.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;

import com.petcare.module.boarding.repository.BoardingBookingRepository;
import com.petcare.module.boarding.repository.KennelRepository;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;

/**
 * Sức chứa theo đêm (BR-LT-01, 03): sức chứa hiện tại = chuồng không bảo trì, số chiếm chỗ từng đêm đếm bằng một câu
 * (docs/adr/0065). Chỉ đọc; bên gọi giữ khóa {@code branches} khi cần kết quả không đổi.
 */
@Component
public class BoardingCapacity {

    /** Một đêm: {@code remaining} không âm dù chuồng bị chuyển bảo trì làm {@code occupied > capacity}. */
    public record Night(LocalDate date, int capacity, int occupied) {
        public int remaining() {
            return Math.max(0, capacity - occupied);
        }
    }

    private final KennelRepository kennels;
    private final BoardingBookingRepository bookings;
    private final SystemConfigApi configs;

    public BoardingCapacity(KennelRepository kennels, BoardingBookingRepository bookings, SystemConfigApi configs) {
        this.kennels = kennels;
        this.bookings = bookings;
        this.configs = configs;
    }

    /** Các đêm {@code [from, to)} (to loại trừ). */
    public List<Night> nights(Long branchId, Long kennelTypeId, LocalDate from, LocalDate to, LocalDate today) {
        int capacity = kennels.countCapacity(branchId, kennelTypeId);
        LocalDate overdueUntil = today.plusDays(configs.getInt(ConfigKey.BOARDING_OVERDUE_HOLD_NIGHTS));
        return bookings.countOccupiedByNight(branchId, kennelTypeId, from, to, today, overdueUntil).stream()
                .map(n -> new Night(n.getNight(), capacity, n.getOccupied())).toList();
    }

    /** Đêm đã hết chỗ (chưa tính đặt chỗ mới): {@code occupied ≥ capacity}. */
    public List<LocalDate> shortNights(Long branchId, Long kennelTypeId, LocalDate from, LocalDate to,
            LocalDate today) {
        return nights(branchId, kennelTypeId, from, to, today).stream()
                .filter(n -> n.occupied() >= n.capacity()).map(Night::date).toList();
    }
}
