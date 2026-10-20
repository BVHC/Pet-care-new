package com.petcare.module.appointment.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.appointment.dto.BookingRestrictionResponse;
import com.petcare.module.appointment.dto.RestrictionStatusResponse;
import com.petcare.module.appointment.entity.BookingRestriction;

/**
 * {@link BookingRestrictionMapper} (docs/adr/0055): mỗi trường của hợp đồng lấy đúng cột — giá trị khác nhau ở mọi trường
 * nên map nhầm nguồn là đỏ; cột gỡ sớm không lọt ra; {@code none()} = không bị hạn chế, {@code restriction = null}.
 */
class BookingRestrictionMapperTest {

    private static final Instant STARTS = Instant.parse("2026-10-15T02:00:00.123456Z");
    private static final Instant ENDS = Instant.parse("2026-11-14T02:00:00.654321Z");

    private final BookingRestrictionMapper mapper = Mappers.getMapper(BookingRestrictionMapper.class);

    @Test
    void everyContractFieldComesFromItsColumn() {
        BookingRestriction restriction = restriction(11L, 22L, 4);
        restriction.lift(Instant.parse("2026-10-20T00:00:00Z"), 33L, "Lý do nội bộ");

        assertThat(mapper.toResponse(restriction))
                .isEqualTo(new BookingRestrictionResponse(11L, 22L, STARTS, ENDS, 4));
        assertThat(BookingRestrictionResponse.class.getRecordComponents()).extracting("name")
                .containsExactly("restrictionId", "customerId", "startsAt", "endsAt", "violationCount");
    }

    @Test
    void activeWrapsTheRowAndNoneHasNoRestriction() {
        BookingRestriction restriction = restriction(11L, 22L, 3);

        assertThat(mapper.active(restriction)).isEqualTo(new RestrictionStatusResponse(true,
                new BookingRestrictionResponse(11L, 22L, STARTS, ENDS, 3)));
        assertThat(mapper.none()).isEqualTo(new RestrictionStatusResponse(false, null));
    }

    @Test
    void smallintCountWidensToInt() {
        assertThat(mapper.toResponse(restriction(1L, 2L, 40_000)).violationCount()).isEqualTo(Short.MAX_VALUE);
    }

    private static BookingRestriction restriction(long id, long customerId, int violations) {
        BookingRestriction restriction = BookingRestriction.start(customerId, STARTS, ENDS, violations);
        ReflectionTestUtils.setField(restriction, "id", id);
        return restriction;
    }
}
