package com.petcare.module.appointment.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.appointment.dto.BookingRestrictionResponse;
import com.petcare.module.appointment.dto.RestrictionStatusResponse;
import com.petcare.module.appointment.entity.BookingRestriction;

/**
 * {@code BookingRestriction} → hợp đồng appointment-v1 ({@code BookingRestriction}, {@code RestrictionStatus};
 * docs/adr/0055). {@code unmappedTargetPolicy = ERROR}: thiếu một trường của hợp đồng là lỗi biên dịch. Các cột gỡ sớm
 * không có trong hợp đồng nên không map.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface BookingRestrictionMapper {

    @Mapping(target = "restrictionId", source = "id")
    @Mapping(target = "customerId", source = "customerId")
    @Mapping(target = "startsAt", source = "startsAt")
    @Mapping(target = "endsAt", source = "endsAt")
    @Mapping(target = "violationCount", source = "violationCount")
    BookingRestrictionResponse toResponse(BookingRestriction restriction);

    /**
     * Đang bị hạn chế bởi {@code restriction} (dòng hiệu lực tại thời điểm xem). Default method: MapStruct không dựng được
     * record này từ một hằng + một nguồn cùng tên ("no write accessor"); phần map trường vẫn qua {@link #toResponse}.
     */
    default RestrictionStatusResponse active(BookingRestriction restriction) {
        return new RestrictionStatusResponse(true, toResponse(restriction));
    }

    /** Không bị hạn chế: chưa từng, đã hết hạn hoặc đã gỡ. */
    default RestrictionStatusResponse none() {
        return new RestrictionStatusResponse(false, null);
    }
}
