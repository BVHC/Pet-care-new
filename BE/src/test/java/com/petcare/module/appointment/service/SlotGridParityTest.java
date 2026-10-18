package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.petcare.module.branch.api.BranchQueryApi.TimeRange;
import com.petcare.module.branch.schedule.BranchSchedule;

/**
 * {@link SlotGrid} (appointment) và {@code BranchSchedule.isGeneratedSlot} (branch — quota riêng của khung, BR-LH-03)
 * phải sinh cùng một tập khung (docs/adr/0041 mục 6). Hai lớp ở hai module nên không dùng chung code; test này (chỉ ở
 * test, main không import chéo) giữ chúng khớp nhau, khuôn {@code NotificationTemplateSyntaxParityTest}.
 * <p>
 * Sinh ngẫu nhiên (seed cố định) các ngày có 0–2 khoảng hợp lệ như BR-CN-02 cho phép (giờ mở trước giờ đóng, không
 * chồng nhau, được nối liền, phút lẻ), rồi so <b>từng phút</b> trong ngày.
 */
class SlotGridParityTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 19);   // thứ Hai
    private static final int MINUTES_PER_DAY = 24 * 60;

    @Test
    void slotGridAgreesWithBranchScheduleMinuteByMinute() {
        Random random = new Random(20261015L);
        for (int n = 0; n < 10_000; n++) {
            assertAgree(randomDay(random));
        }
    }

    @Test
    void edgeCasesAgree() {
        assertAgree(List.of());
        assertAgree(List.of(range(8, 0, 12, 0), range(12, 0, 14, 0)));
        assertAgree(List.of(range(8, 15, 9, 44)));
        assertAgree(List.of(range(0, 0, 23, 59)));
        assertAgree(List.of(range(23, 29, 23, 59)));
        assertAgree(List.of(range(8, 0, 8, 29)));
    }

    private static void assertAgree(List<TimeRange> ranges) {
        BranchSchedule schedule = new BranchSchedule(
                List.of(new BranchSchedule.Version(DAY, Map.of(DAY.getDayOfWeek().getValue(), ranges))), Set.of());
        List<LocalTime> expected = new ArrayList<>();
        for (int minute = 0; minute < MINUTES_PER_DAY; minute++) {
            LocalTime start = LocalTime.of(minute / 60, minute % 60);
            boolean branch = schedule.isGeneratedSlot(DAY, start);
            assertThat(SlotGrid.isSlotStart(ranges, start)).as("%s @ %s", ranges, start).isEqualTo(branch);
            if (branch) {
                expected.add(start);
            }
        }
        assertThat(SlotGrid.slotStarts(ranges)).as("%s", ranges).containsExactlyElementsOf(expected);
    }

    /** 0–2 khoảng hợp lệ: mở < đóng, khoảng sau mở không sớm hơn khoảng trước đóng (nối liền được). */
    private static List<TimeRange> randomDay(Random random) {
        int count = random.nextInt(3);
        List<TimeRange> ranges = new ArrayList<>();
        int cursor = random.nextInt(12 * 60);
        for (int i = 0; i < count; i++) {
            int open = cursor + (random.nextInt(4) == 0 ? 0 : random.nextInt(180));
            int length = 1 + random.nextInt(random.nextBoolean() ? 60 : 300);
            int close = open + length;
            if (close >= MINUTES_PER_DAY) {
                break;
            }
            ranges.add(new TimeRange(LocalTime.of(open / 60, open % 60), LocalTime.of(close / 60, close % 60)));
            cursor = close;
        }
        if (random.nextBoolean()) {
            java.util.Collections.reverse(ranges);
        }
        return ranges;
    }

    private static TimeRange range(int openHour, int openMinute, int closeHour, int closeMinute) {
        return new TimeRange(LocalTime.of(openHour, openMinute), LocalTime.of(closeHour, closeMinute));
    }
}
