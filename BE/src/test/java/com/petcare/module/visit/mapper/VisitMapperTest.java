package com.petcare.module.visit.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.Species;
import com.petcare.module.visit.dto.AssignResult;
import com.petcare.module.visit.dto.VisitResponse;
import com.petcare.module.visit.entity.PriorityClass;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitStatus;

/** {@link VisitMapper}: mỗi trường một giá trị riêng nên map nhầm nguồn là đỏ (docs/adr/0059 E10). */
class VisitMapperTest {

    private static final Instant CHECKED_IN = Instant.parse("2026-10-20T01:55:00.123456Z");
    private static final Instant SORT = Instant.parse("2026-10-20T02:00:00Z");
    private static final Instant CALLED = Instant.parse("2026-10-20T02:05:00Z");
    private static final Instant COMPLETED = Instant.parse("2026-10-20T02:40:00Z");
    private static final Instant CANCELLED = Instant.parse("2026-10-20T02:10:00Z");

    private final VisitMapper mapper = Mappers.getMapper(VisitMapper.class);

    @Test
    void everyFieldFromItsOwnSource() {
        Visit visit = Visit.checkIn("TN-2610-00042", 3L, 88L, 12L, 501L, ServiceGroup.GROOMING,
                PriorityClass.APPOINTMENT, SORT, 70L, CHECKED_IN);
        ReflectionTestUtils.setField(visit, "id", 9001L);
        ReflectionTestUtils.setField(visit, "status", VisitStatus.CANCELLED);
        ReflectionTestUtils.setField(visit, "assigneeId", 45L);
        ReflectionTestUtils.setField(visit, "needsReassign", true);
        ReflectionTestUtils.setField(visit, "calledAt", CALLED);
        ReflectionTestUtils.setField(visit, "completedAt", COMPLETED);
        ReflectionTestUtils.setField(visit, "cancelledAt", CANCELLED);
        ReflectionTestUtils.setField(visit, "cancelReason", "Khách bận việc");

        VisitResponse response = mapper.toResponse(visit,
                new VisitMapper.Parties("Nguyễn Văn An", "Milu", Species.CAT, "Trần Bình"), 7001L);

        assertThat(response).isEqualTo(new VisitResponse(9001L, "TN-2610-00042", 3L, 88L, "Nguyễn Văn An", 12L, "Milu",
                Species.CAT, 501L, ServiceGroup.GROOMING, VisitStatus.CANCELLED, PriorityClass.APPOINTMENT, SORT, 45L,
                "Trần Bình", true, 7001L, CHECKED_IN, CALLED, COMPLETED, CANCELLED, "Khách bận việc"));
    }

    /** visit-v1 #5 (docs/adr/0061): {@code visit} gán thẳng, cờ offline giữ đúng giá trị (không hằng). */
    @Test
    void assignResultKeepsTheVisitAndTheWarning() {
        Visit visit = Visit.checkIn("TN-2610-00044", 3L, 88L, 12L, null, ServiceGroup.MEDICAL, PriorityClass.WALK_IN,
                CHECKED_IN, 70L, CHECKED_IN);
        ReflectionTestUtils.setField(visit, "id", 9003L);
        VisitResponse response = mapper.toResponse(visit,
                new VisitMapper.Parties("Nguyễn Văn An", "Milu", Species.DOG, null), 7003L);

        AssignResult offline = mapper.toAssignResult(response, true);
        AssignResult online = mapper.toAssignResult(response, false);

        assertThat(offline.visit()).isSameAs(response);
        assertThat(offline.assigneeOffline()).isTrue();
        assertThat(online.assigneeOffline()).isFalse();
    }

    @Test
    void freshWalkInKeepsNullablesNullAndNeedsReassignFalse() {
        Visit visit = Visit.checkIn("TN-2610-00043", 3L, 88L, 12L, null, ServiceGroup.MEDICAL, PriorityClass.WALK_IN,
                CHECKED_IN, 70L, CHECKED_IN);
        ReflectionTestUtils.setField(visit, "id", 9002L);

        VisitResponse response = mapper.toResponse(visit,
                new VisitMapper.Parties("Nguyễn Văn An", "Milu", Species.DOG, null), 7002L);

        assertThat(response.appointmentId()).isNull();
        assertThat(response.assigneeId()).isNull();
        assertThat(response.assigneeName()).isNull();
        assertThat(response.calledAt()).isNull();
        assertThat(response.completedAt()).isNull();
        assertThat(response.cancelledAt()).isNull();
        assertThat(response.cancelReason()).isNull();
        assertThat(response.needsReassign()).isFalse();
        assertThat(response.status()).isEqualTo(VisitStatus.WAITING);
        assertThat(response.orderId()).isEqualTo(7002L);
        assertThat(response.visitId()).isEqualTo(9002L);
    }
}
