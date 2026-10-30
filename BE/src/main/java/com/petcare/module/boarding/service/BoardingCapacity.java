package com.petcare.module.boarding.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.entity.BoardingBookingStatus;
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

    /**
     * Đặt chỗ {@code BOOKED} của loại chuồng bị thiếu chỗ: còn ít nhất một đêm (từ hôm nay) có {@code occupied >
     * capacity} (bảo trì chuồng, đặt chỗ quá hạn đón chiếm chỗ — BR-LT-01, 10). {@code checkInUntil != null} giới hạn
     * ở đặt chỗ nhận không muộn hơn ngày đó. Theo ngày nhận rồi id.
     */
    public List<BoardingBooking> shortageBookings(Long branchId, Long kennelTypeId, LocalDate today,
            LocalDate checkInUntil) {
        List<BoardingBooking> booked = bookings
                .findByBranchIdAndKennelTypeIdAndStatusAndCheckOutDateAfterOrderByCheckInDateAscIdAsc(branchId,
                        kennelTypeId, BoardingBookingStatus.BOOKED, today).stream()
                .filter(b -> checkInUntil == null || !b.getCheckInDate().isAfter(checkInUntil)).toList();
        if (booked.isEmpty()) {
            return List.of();
        }
        LocalDate from = booked.stream().map(b -> latest(b.getCheckInDate(), today)).min(LocalDate::compareTo).get();
        LocalDate to = booked.stream().map(BoardingBooking::getCheckOutDate).max(LocalDate::compareTo).get();
        Set<LocalDate> over = nights(branchId, kennelTypeId, from, to, today).stream()
                .filter(n -> n.occupied() > n.capacity()).map(Night::date).collect(Collectors.toSet());
        return booked.stream().filter(b -> {
            for (LocalDate d = latest(b.getCheckInDate(), today); d.isBefore(b.getCheckOutDate()); d = d.plusDays(1)) {
                if (over.contains(d)) {
                    return true;
                }
            }
            return false;
        }).toList();
    }

    private static LocalDate latest(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    /** Đêm đã hết chỗ (chưa tính đặt chỗ mới): {@code occupied ≥ capacity}. */
    public List<LocalDate> shortNights(Long branchId, Long kennelTypeId, LocalDate from, LocalDate to,
            LocalDate today) {
        return nights(branchId, kennelTypeId, from, to, today).stream()
                .filter(n -> n.occupied() >= n.capacity()).map(Night::date).toList();
    }
}
