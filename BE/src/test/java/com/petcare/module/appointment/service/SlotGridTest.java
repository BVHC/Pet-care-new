package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.petcare.module.branch.api.BranchQueryApi.TimeRange;

/**
 * Biên của {@link SlotGrid} (BR-LH-02): bước 30 phút từ giờ mở của từng khoảng, khung nằm trọn trong khoảng, khung cuối
 * kết thúc ≤ giờ đóng. Ngữ nghĩa so với branch: {@code SlotGridParityTest}.
 */
class SlotGridTest {

    private static TimeRange range(String open, String close) {
        return new TimeRange(LocalTime.parse(open), LocalTime.parse(close));
    }

    private static List<LocalTime> times(String... values) {
        return java.util.Arrays.stream(values).map(LocalTime::parse).toList();
    }

    @Test
    void twoRangesWithLunchBreak() {
        List<TimeRange> day = List.of(range("08:00", "10:00"), range("14:00", "15:30"));

        assertThat(SlotGrid.slotStarts(day)).containsExactlyElementsOf(
                times("08:00", "08:30", "09:00", "09:30", "14:00", "14:30", "15:00"));
    }

    @Test
    void lastSlotEndsExactlyAtClose() {
        assertThat(SlotGrid.slotStarts(List.of(range("08:00", "09:00")))).containsExactlyElementsOf(
                times("08:00", "08:30"));
        assertThat(SlotGrid.isSlotStart(List.of(range("08:00", "09:00")), LocalTime.parse("08:30"))).isTrue();
    }

    @Test
    void slotNeverCrossesClose() {
        List<TimeRange> day = List.of(range("08:00", "09:15"));

        assertThat(SlotGrid.slotStarts(day)).containsExactlyElementsOf(times("08:00", "08:30"));
        assertThat(SlotGrid.isSlotStart(day, LocalTime.parse("09:00"))).isFalse();
    }

    @Test
    void stepsFromOddOpeningTime() {
        List<TimeRange> day = List.of(range("08:15", "09:45"));

        assertThat(SlotGrid.slotStarts(day)).containsExactlyElementsOf(times("08:15", "08:45", "09:15"));
        assertThat(SlotGrid.isSlotStart(day, LocalTime.parse("08:30"))).isFalse();
        assertThat(SlotGrid.isSlotStart(day, LocalTime.parse("08:45"))).isTrue();
    }

    @Test
    void rangeShorterThanOneSlotHasNoSlot() {
        assertThat(SlotGrid.slotStarts(List.of(range("08:00", "08:29")))).isEmpty();
    }

    @Test
    void adjacentRangesDoNotDuplicateBoundary() {
        List<TimeRange> day = List.of(range("08:00", "09:00"), range("09:00", "10:00"));

        assertThat(SlotGrid.slotStarts(day)).containsExactlyElementsOf(
                times("08:00", "08:30", "09:00", "09:30"));
    }

    @Test
    void unsortedRangesAreSorted() {
        List<TimeRange> day = List.of(range("14:00", "15:00"), range("08:00", "09:00"));

        assertThat(SlotGrid.slotStarts(day)).containsExactlyElementsOf(
                times("08:00", "08:30", "14:00", "14:30"));
    }

    @Test
    void closedDayHasNoSlot() {
        assertThat(SlotGrid.slotStarts(List.of())).isEmpty();
        assertThat(SlotGrid.isSlotStart(List.of(), LocalTime.parse("08:00"))).isFalse();
    }

    @Test
    void lateEveningRangeDoesNotWrapPastMidnight() {
        assertThat(SlotGrid.slotStarts(List.of(range("23:00", "23:59")))).containsExactlyElementsOf(times("23:00"));
    }

    @Test
    void startOffTheGridIsNotASlot() {
        List<TimeRange> day = List.of(range("08:00", "12:00"));

        assertThat(SlotGrid.isSlotStart(day, LocalTime.parse("08:10"))).isFalse();
        assertThat(SlotGrid.isSlotStart(day, LocalTime.of(8, 30, 1))).isFalse();
        assertThat(SlotGrid.isSlotStart(day, LocalTime.of(8, 30, 0, 1))).isFalse();
        assertThat(SlotGrid.isSlotStart(day, LocalTime.parse("07:30"))).isFalse();
        assertThat(SlotGrid.isSlotStart(day, LocalTime.parse("12:00"))).isFalse();
    }

    @Test
    void resultIsImmutable() {
        List<LocalTime> starts = SlotGrid.slotStarts(List.of(range("08:00", "09:00")));

        assertThatThrownBy(() -> starts.add(LocalTime.NOON)).isInstanceOf(UnsupportedOperationException.class);
    }

    /** Dữ liệu BR-CN-02 đã chặn lúc ghi: gặp thì báo lỗi, không tự sinh khung. */
    @Test
    void invalidRangesAreRejected() {
        assertThatThrownBy(() -> SlotGrid.slotStarts(List.of(range("10:00", "10:00"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlotGrid.slotStarts(List.of(range("10:00", "09:00"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlotGrid.isSlotStart(
                List.of(range("08:00", "10:00"), range("09:30", "11:00")), LocalTime.parse("08:00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullsAreRejected() {
        assertThatThrownBy(() -> SlotGrid.slotStarts(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SlotGrid.isSlotStart(List.of(), null)).isInstanceOf(NullPointerException.class);
    }
}
