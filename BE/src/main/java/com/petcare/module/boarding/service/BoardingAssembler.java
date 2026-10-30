package com.petcare.module.boarding.service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.petcare.module.boarding.dto.BookingResponse;
import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.entity.BoardingBookingStatus;
import com.petcare.module.boarding.entity.Kennel;
import com.petcare.module.boarding.repository.BoardingBookingRepository;
import com.petcare.module.boarding.repository.KennelRepository;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.sales.api.BoardingOrderApi;

/**
 * Ghép {@link BoardingBooking} với tên khách, thú, loại chuồng, mã chuồng (docs/adr/0065). Tra theo lô ≤
 * {@value #BATCH} id mỗi lần gọi module khác (khuôn {@code ScheduleImpactFinder}); id thiếu là dữ liệu hỏng →
 * {@link IllegalStateException}, rollback use case của bên gọi. {@code activeOrderId} là Order OPEN / PENDING của đặt chỗ (BoardingOrderApi, ADR-0086).
 */
@Component
public class BoardingAssembler {

    static final int BATCH = 100;

    private final CustomerQueryApi customers;
    private final PetQueryApi pets;
    private final CatalogQueryApi catalog;
    private final KennelRepository kennels;
    private final BoardingBookingRepository bookings;
    private final BoardingOrderApi boardingOrders;

    public BoardingAssembler(CustomerQueryApi customers, PetQueryApi pets, CatalogQueryApi catalog,
            KennelRepository kennels, BoardingBookingRepository bookings,
            BoardingOrderApi boardingOrders) {
        this.customers = customers;
        this.pets = pets;
        this.catalog = catalog;
        this.kennels = kennels;
        this.bookings = bookings;
        this.boardingOrders = boardingOrders;
    }

    public BookingResponse toResponse(BoardingBooking booking, LocalDate today) {
        return toResponses(List.of(booking), today).get(0);
    }

    public List<BookingResponse> toResponses(List<BoardingBooking> rows, LocalDate today) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, CustomerContact> customerById = fetch(ids(rows, BoardingBooking::getCustomerId),
                customers::findContacts, CustomerContact::customerId, "khách");
        Map<Long, PetSummary> petById = fetch(ids(rows, BoardingBooking::getPetId), pets::findPets,
                PetSummary::petId, "thú cưng");
        Map<Long, ServiceInfo> typeById = fetch(ids(rows, BoardingBooking::getKennelTypeId), catalog::findServices,
                ServiceInfo::serviceId, "loại chuồng");
        Set<Long> kennelIds = new HashSet<>();
        rows.stream().map(BoardingBooking::getKennelId).filter(java.util.Objects::nonNull).forEach(kennelIds::add);
        Map<Long, String> kennelCodes = kennelIds.isEmpty() ? Map.of()
                : kennels.findAllById(kennelIds).stream().collect(Collectors.toMap(Kennel::getId, Kennel::getCode));
        List<Long> inStay = rows.stream().filter(b -> b.getStatus() == BoardingBookingStatus.CHECKED_IN
                || b.getStatus() == BoardingBookingStatus.OVERDUE).map(BoardingBooking::getId).toList();
        Set<Long> logged = inStay.isEmpty() ? Set.of()
                : new HashSet<>(bookings.findBookingIdsWithCareLogOn(inStay, today));
        Map<Long, Long> activeOrders = new java.util.HashMap<>();
        List<Long> bookingIds = ids(rows, BoardingBooking::getId);
        for (int from = 0; from < bookingIds.size(); from += BATCH) {
            activeOrders.putAll(boardingOrders.findActiveOrderIds(
                    bookingIds.subList(from, Math.min(bookingIds.size(), from + BATCH))));
        }
        return rows.stream().map(b -> new BookingResponse(b.getId(), b.getCode(), b.getCustomerId(),
                customerById.get(b.getCustomerId()).fullName(), b.getPetId(), petById.get(b.getPetId()).name(),
                b.getBranchId(), b.getKennelTypeId(), typeById.get(b.getKennelTypeId()).name(), b.getKennelId(),
                b.getKennelId() == null ? null : kennelCodes.get(b.getKennelId()), b.getCheckInDate(),
                b.getCheckOutDate(), b.getActualCheckInAt(), b.getActualCheckOutAt(), b.getNightlyPrice(),
                b.getStatus(), b.getEndReason(), b.getChannel(), b.isLateCancel(), b.getCancelSource(),
                b.getCancelReason(), b.getOverdueSince(), activeOrders.get(b.getId()),
                inStay.contains(b.getId()) && !logged.contains(b.getId()))).toList();
    }

    private static List<Long> ids(List<BoardingBooking> rows, Function<BoardingBooking, Long> key) {
        return rows.stream().map(key).distinct().toList();
    }

    private static <T> Map<Long, T> fetch(List<Long> ids, Function<List<Long>, List<T>> loader,
            Function<T, Long> key, String what) {
        Map<Long, T> result = new java.util.HashMap<>();
        for (int from = 0; from < ids.size(); from += BATCH) {
            for (T item : loader.apply(ids.subList(from, Math.min(ids.size(), from + BATCH)))) {
                if (result.put(key.apply(item), item) != null) {
                    throw new IllegalStateException("Module trả " + what + " #" + key.apply(item) + " hai lần");
                }
            }
        }
        for (Long id : ids) {
            if (!result.containsKey(id)) {
                throw new IllegalStateException("Đặt chỗ lưu trú trỏ tới " + what + " không có: #" + id);
            }
        }
        return result;
    }
}
