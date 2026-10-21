package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.visit.entity.PriorityClass;
import com.petcare.module.visit.entity.Visit;

/**
 * {@link QueueReorder} (docs/adr/0061 Q1, Q4). Mỗi ca áp thay đổi rồi sắp lại theo BR-TN-04 để kiểm kết quả cuối, không
 * chỉ kiểm con số: lượt được sắp đứng ngay trước lượt đích, khóa từ lượt đó trở đi tăng chặt, lượt khác không đổi.
 */
class QueueReorderTest {

    private static final Instant NINE = Instant.parse("2026-10-21T02:00:00Z");
    private static final Instant NINE_THIRTY = Instant.parse("2026-10-21T02:30:00Z");
    private static final Instant TEN = Instant.parse("2026-10-21T03:00:00Z");

    @Test
    void sameClassWithRoomTakesTargetKeyMinusOneMicro() {
        Visit a = visit(1, PriorityClass.WALK_IN, NINE);
        Visit b = visit(2, PriorityClass.WALK_IN, NINE_THIRTY);
        Visit c = visit(3, PriorityClass.WALK_IN, TEN);

        List<QueueReorder.Change> changes = QueueReorder.placeBefore(List.of(a, b, c), c, b);

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).visit()).isSameAs(c);
        assertThat(changes.get(0).queueSortKey()).isEqualTo(NINE_THIRTY.minusNanos(1_000));
        assertThat(apply(changes, a, b, c)).containsExactly(a, c, b);
    }

    @Test
    void appointmentsSharingOneSlotKeyAreSeparatedByShiftingTheTail() {
        // Ba lượt hẹn cùng khung 9:00 có cùng khóa (QueuePlacement: khóa = giờ hẹn), hòa nhau theo id.
        Visit a = visit(10, PriorityClass.APPOINTMENT, NINE);
        Visit b = visit(11, PriorityClass.APPOINTMENT, NINE);
        Visit c = visit(12, PriorityClass.APPOINTMENT, NINE);

        List<QueueReorder.Change> changes = QueueReorder.placeBefore(List.of(a, b, c), c, b);

        assertThat(apply(changes, a, b, c)).containsExactly(a, c, b);
        assertThat(c.getQueueSortKey()).isAfter(a.getQueueSortKey());
        assertThat(b.getQueueSortKey()).isAfter(c.getQueueSortKey());
        assertThat(a.getQueueSortKey()).as("lượt đứng trước không bị đụng").isEqualTo(NINE);
    }

    @Test
    void oneMicroGapBeforeTheTargetStillYieldsAStrictOrder() {
        Visit a = visit(1, PriorityClass.WALK_IN, NINE);
        Visit b = visit(2, PriorityClass.WALK_IN, NINE.plusNanos(1_000));
        Visit c = visit(3, PriorityClass.WALK_IN, TEN);

        List<QueueReorder.Change> changes = QueueReorder.placeBefore(List.of(a, b, c), c, b);

        assertThat(apply(changes, a, b, c)).containsExactly(a, c, b);
        assertThat(keys(a, c, b)).isSortedAccordingTo(Instant::compareTo).doesNotHaveDuplicates();
    }

    @Test
    void shiftingStopsAtTheFirstVisitThatIsAlreadyLater() {
        Visit a = visit(1, PriorityClass.APPOINTMENT, NINE);
        Visit b = visit(2, PriorityClass.APPOINTMENT, NINE);
        Visit c = visit(3, PriorityClass.APPOINTMENT, NINE);
        Visit d = visit(4, PriorityClass.APPOINTMENT, TEN);
        Visit moved = visit(5, PriorityClass.APPOINTMENT, TEN.plusSeconds(60));

        List<QueueReorder.Change> changes = QueueReorder.placeBefore(List.of(a, b, c, d, moved), moved, b);

        assertThat(changes).extracting(QueueReorder.Change::visit).containsExactly(moved, b, c);
        assertThat(apply(changes, a, b, c, d, moved)).containsExactly(a, moved, b, c, d);
        assertThat(d.getQueueSortKey()).isEqualTo(TEN);
    }

    @Test
    void walkInPlacedBeforeAnAppointmentTakesTheAppointmentClass() {
        Visit appointment = visit(1, PriorityClass.APPOINTMENT, TEN);
        Visit walkIn = visit(2, PriorityClass.WALK_IN, NINE);

        List<QueueReorder.Change> changes = QueueReorder.placeBefore(List.of(appointment, walkIn), walkIn, appointment);

        assertThat(changes.get(0).priorityClass()).isEqualTo(PriorityClass.APPOINTMENT);
        assertThat(apply(changes, appointment, walkIn)).containsExactly(walkIn, appointment);
    }

    @Test
    void appointmentPlacedBeforeAWalkInTakesTheWalkInClassAndIgnoresTheHigherClassBeforeIt() {
        Visit earlyAppointment = visit(1, PriorityClass.APPOINTMENT, TEN);
        Visit moved = visit(2, PriorityClass.APPOINTMENT, NINE);
        Visit walkIn = visit(3, PriorityClass.WALK_IN, NINE_THIRTY);

        List<QueueReorder.Change> changes = QueueReorder.placeBefore(List.of(earlyAppointment, moved, walkIn), moved,
                walkIn);

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).priorityClass()).isEqualTo(PriorityClass.WALK_IN);
        assertThat(changes.get(0).queueSortKey()).isEqualTo(NINE_THIRTY.minusNanos(1_000));
        assertThat(apply(changes, earlyAppointment, moved, walkIn)).containsExactly(earlyAppointment, moved, walkIn);
    }

    @Test
    void emergenciesReorderAmongThemselves() {
        Visit first = visit(1, PriorityClass.EMERGENCY, NINE);
        Visit second = visit(2, PriorityClass.EMERGENCY, TEN);

        List<QueueReorder.Change> changes = QueueReorder.placeBefore(List.of(first, second), second, first);

        assertThat(apply(changes, first, second)).containsExactly(second, first);
        assertThat(changes.get(0).priorityClass()).isEqualTo(PriorityClass.EMERGENCY);
    }

    @Test
    void alreadyRightBeforeInTheSameClassChangesNothing() {
        Visit a = visit(1, PriorityClass.APPOINTMENT, NINE);
        Visit b = visit(2, PriorityClass.APPOINTMENT, NINE);

        assertThat(QueueReorder.placeBefore(List.of(b, a), a, b)).isEmpty();
    }

    @Test
    void rightBeforeButInAnotherClassStillMovesIntoTheTargetClass() {
        Visit appointment = visit(1, PriorityClass.APPOINTMENT, TEN);
        Visit walkIn = visit(2, PriorityClass.WALK_IN, NINE);
        // appointment đã đứng ngay trước walkIn theo BR-TN-04 nhưng khác hạng: "đặt trước walkIn" vẫn đổi hạng.

        List<QueueReorder.Change> changes = QueueReorder.placeBefore(List.of(appointment, walkIn), appointment, walkIn);

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).priorityClass()).isEqualTo(PriorityClass.WALK_IN);
    }

    @Test
    void movingTheHeadFurtherBackLandsRightBeforeTheTarget() {
        Visit a = visit(1, PriorityClass.WALK_IN, NINE);
        Visit b = visit(2, PriorityClass.WALK_IN, NINE_THIRTY);
        Visit c = visit(3, PriorityClass.WALK_IN, TEN);

        assertThat(apply(QueueReorder.placeBefore(List.of(a, b, c), a, c), a, b, c)).containsExactly(b, a, c);
    }

    @Test
    void doesNotMutateTheVisitsItself() {
        Visit a = visit(1, PriorityClass.WALK_IN, NINE);
        Visit b = visit(2, PriorityClass.WALK_IN, TEN);

        QueueReorder.placeBefore(List.of(a, b), b, a);

        assertThat(b.getQueueSortKey()).isEqualTo(TEN);
        assertThat(b.getPriorityClass()).isEqualTo(PriorityClass.WALK_IN);
    }

    @Test
    void programmingErrorsAreRejected() {
        Visit a = visit(1, PriorityClass.WALK_IN, NINE);
        Visit emergency = visit(2, PriorityClass.EMERGENCY, NINE);
        Visit outside = visit(3, PriorityClass.WALK_IN, TEN);

        assertThatThrownBy(() -> QueueReorder.placeBefore(List.of(a), a, a))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueueReorder.placeBefore(List.of(a, emergency), a, emergency))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueueReorder.placeBefore(List.of(a), a, outside))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Visit visit(long id, PriorityClass priorityClass, Instant key) {
        Visit visit = Visit.checkIn("TN-2610-" + id, 3L, 88L, 100 + id, null, ServiceGroup.MEDICAL, priorityClass, key,
                70L, key);
        ReflectionTestUtils.setField(visit, "id", id);
        return visit;
    }

    /** Áp thay đổi lên entity rồi trả thứ tự BR-TN-04. */
    private static List<Visit> apply(List<QueueReorder.Change> changes, Visit... all) {
        changes.forEach(change -> change.visit().reorder(change.priorityClass(), change.queueSortKey()));
        List<Visit> sorted = new ArrayList<>(Arrays.asList(all));
        sorted.sort(QueueOrder.BR_TN_04);
        return sorted;
    }

    private static List<Instant> keys(Visit... visits) {
        return Arrays.stream(visits).map(Visit::getQueueSortKey).toList();
    }
}
