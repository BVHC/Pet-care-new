package com.petcare.module.branch.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.petcare.module.branch.api.BranchQueryApi.TimeRange;
import com.petcare.module.branch.schedule.BranchSchedule.Version;

/**
 * BR-CN-02 (giờ mở cửa theo thứ, 0–2 khoảng), BR-CN-03 (ngày nghỉ), BR-CN-04 (phiên bản theo ngày hiệu lực),
 * BR-LH-02 (khung 30 phút). Tuần dùng trong test: 2026-10-12 là thứ Hai, 2026-10-18 là Chủ nhật.
 */
class BranchScheduleTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 12);
    private static final LocalDate TUE = LocalDate.of(2026, 10, 13);
    private static final LocalDate WED = LocalDate.of(2026, 10, 14);
    private static final LocalDate SAT = LocalDate.of(2026, 10, 17);
    private static final LocalDate SUN = LocalDate.of(2026, 10, 18);

    private static TimeRange range(String open, String close) {
        return new TimeRange(LocalTime.parse(open), LocalTime.parse(close));
    }

    /** Thứ Hai–Thứ Bảy 08:00–12:00 và 14:00–19:00, Chủ nhật nghỉ, hiệu lực từ đầu năm. */
    private static Version split() {
        List<TimeRange> day = List.of(range("08:00", "12:00"), range("14:00", "19:00"));
        return new Version(LocalDate.of(2026, 1, 1), Map.of(1, day, 2, day, 3, day, 4, day, 5, day, 6, day));
    }

    /** Thứ Hai–Thứ Sáu 09:00–17:00 liền một khoảng, hiệu lực từ 2026-11-01. */
    private static Version straightFromNovember() {
        List<TimeRange> day = List.of(range("09:00", "17:00"));
        return new Version(LocalDate.of(2026, 11, 1), Map.of(1, day, 2, day, 3, day, 4, day, 5, day));
    }

    private static BranchSchedule schedule(Set<LocalDate> holidays, Version... versions) {
        return new BranchSchedule(List.of(versions), holidays);
    }

    private static LocalDateTime at(LocalDate date, String time) {
        return date.atTime(LocalTime.parse(time));
    }

    @Test
    void rangesAreSortedAndSundayIsFixedDayOff() {
        BranchSchedule schedule = schedule(Set.of(), split());

        assertThat(schedule.rangesOn(MON)).containsExactly(range("08:00", "12:00"), range("14:00", "19:00"));
        assertThat(schedule.rangesOn(SUN)).isEmpty();
        assertThat(schedule.isOpenOn(SUN)).isFalse();
    }

    @Test
    void unsortedInputIsReturnedInOpeningOrder() {
        Version unsorted = new Version(LocalDate.of(2026, 1, 1),
                Map.of(1, List.of(range("14:00", "19:00"), range("08:00", "12:00"))));

        assertThat(unsorted.rangesOn(MON)).containsExactly(range("08:00", "12:00"), range("14:00", "19:00"));
    }

    @Test
    void noVersionOrBeforeFirstEffectiveDateMeansClosed() {
        assertThat(schedule(Set.of()).rangesOn(MON)).isEmpty();
        assertThat(schedule(Set.of(), split()).rangesOn(LocalDate.of(2025, 12, 31))).isEmpty();
        assertThat(schedule(Set.of()).hasVersions()).isFalse();
        assertThat(schedule(Set.of(), split()).hasVersions()).isTrue();
    }

    @Test
    void latestVersionNotAfterTheDateApplies() {
        BranchSchedule schedule = schedule(Set.of(), split(), straightFromNovember());
        LocalDate lastDayOfOldVersion = LocalDate.of(2026, 10, 31);
        LocalDate firstMondayOfNewVersion = LocalDate.of(2026, 11, 2);
        LocalDate saturdayUnderNewVersion = LocalDate.of(2026, 11, 7);

        assertThat(schedule.rangesOn(lastDayOfOldVersion)).containsExactly(range("08:00", "12:00"),
                range("14:00", "19:00"));
        assertThat(schedule.rangesOn(firstMondayOfNewVersion)).containsExactly(range("09:00", "17:00"));
        assertThat(schedule.rangesOn(saturdayUnderNewVersion)).isEmpty();
    }

    @Test
    void holidayClosesTheWholeDay() {
        BranchSchedule schedule = schedule(Set.of(TUE), split());

        assertThat(schedule.isHoliday(TUE)).isTrue();
        assertThat(schedule.rangesOn(TUE)).isEmpty();
        assertThat(schedule.isOpenAt(at(TUE, "09:00"))).isFalse();
        assertThat(schedule.isOpenAt(at(MON, "09:00"))).isTrue();
    }

    @Test
    void closingTimeIsExclusiveAndOpeningTimeInclusive() {
        BranchSchedule schedule = schedule(Set.of(), split());

        assertThat(schedule.isOpenAt(at(MON, "07:59"))).isFalse();
        assertThat(schedule.isOpenAt(at(MON, "08:00"))).isTrue();
        assertThat(schedule.isOpenAt(at(MON, "11:59"))).isTrue();
        assertThat(schedule.isOpenAt(at(MON, "12:00"))).isFalse();
        assertThat(schedule.isOpenAt(at(MON, "13:00"))).isFalse();
        assertThat(schedule.isOpenAt(at(MON, "14:00"))).isTrue();
        assertThat(schedule.isOpenAt(at(MON, "19:00"))).isFalse();
        assertThat(schedule.isOpenAt(at(SUN, "10:00"))).isFalse();
    }

    @Test
    void slotMustSitInsideOneRange() {
        BranchSchedule schedule = schedule(Set.of(), split());

        assertThat(schedule.slotFits(MON, LocalTime.of(8, 0))).isTrue();
        assertThat(schedule.slotFits(MON, LocalTime.of(11, 30))).isTrue();     // kết thúc đúng 12:00
        assertThat(schedule.slotFits(MON, LocalTime.of(11, 31))).isFalse();
        assertThat(schedule.slotFits(MON, LocalTime.of(11, 45))).isFalse();    // vắt qua giờ nghỉ giữa ca
        assertThat(schedule.slotFits(MON, LocalTime.of(12, 0))).isFalse();
        assertThat(schedule.slotFits(MON, LocalTime.of(13, 30))).isFalse();
        assertThat(schedule.slotFits(MON, LocalTime.of(14, 0))).isTrue();
        assertThat(schedule.slotFits(MON, LocalTime.of(18, 30))).isTrue();     // khung cuối kết thúc đúng 19:00
        assertThat(schedule.slotFits(MON, LocalTime.of(18, 45))).isFalse();
        assertThat(schedule.slotFits(MON, LocalTime.of(7, 30))).isFalse();
        assertThat(schedule.slotFits(SUN, LocalTime.of(9, 0))).isFalse();
        assertThat(schedule.slotFits(MON, LocalTime.of(23, 45))).isFalse();    // không bị tràn qua nửa đêm
    }

    @Test
    void nextOpeningStartIsStrictlyAfterTheGivenMoment() {
        BranchSchedule schedule = schedule(Set.of(), split());

        assertThat(schedule.nextOpeningStart(at(MON, "07:00"))).contains(at(MON, "08:00"));
        assertThat(schedule.nextOpeningStart(at(MON, "08:00"))).contains(at(MON, "14:00"));
        assertThat(schedule.nextOpeningStart(at(MON, "13:00"))).contains(at(MON, "14:00"));
        assertThat(schedule.nextOpeningStart(at(MON, "19:00"))).contains(at(TUE, "08:00"));
        assertThat(schedule.nextOpeningStart(at(SAT, "19:00"))).contains(at(MON.plusDays(7), "08:00"));
    }

    @Test
    void nextOpeningStartSkipsHolidays() {
        BranchSchedule schedule = schedule(Set.of(TUE), split());

        assertThat(schedule.nextOpeningStart(at(MON, "19:00"))).contains(at(WED, "08:00"));
    }

    @Test
    void nextOpeningStartCrossesIntoANewVersion() {
        BranchSchedule schedule = schedule(Set.of(), split(), straightFromNovember());

        // Thứ Bảy 2026-10-31 còn theo bản cũ, Chủ nhật nghỉ, thứ Hai 2026-11-02 mở 09:00 theo bản mới.
        assertThat(schedule.nextOpeningStart(at(LocalDate.of(2026, 10, 31), "19:00")))
                .contains(at(LocalDate.of(2026, 11, 2), "09:00"));
    }

    @Test
    void nextOpeningStartIsEmptyWhenThereAreNoHoursAtAll() {
        assertThat(schedule(Set.of()).nextOpeningStart(at(MON, "07:00"))).isEmpty();
        Version allOff = new Version(LocalDate.of(2026, 1, 1), Map.of());
        assertThat(schedule(Set.of(), allOff).nextOpeningStart(at(MON, "07:00"))).isEmpty();
    }

    @Test
    void withVersionReplacesTheSameEffectiveDateAndLeavesOriginalUntouched() {
        BranchSchedule original = schedule(Set.of(), split());
        Version replacement = new Version(LocalDate.of(2026, 1, 1), Map.of(1, List.of(range("10:00", "11:00"))));

        BranchSchedule changed = original.withVersion(replacement);

        assertThat(changed.rangesOn(MON)).containsExactly(range("10:00", "11:00"));
        assertThat(changed.rangesOn(TUE)).isEmpty();
        assertThat(original.rangesOn(MON)).hasSize(2);
    }

    @Test
    void withHolidayDoesNotMutateTheOriginal() {
        BranchSchedule original = schedule(Set.of(), split());

        BranchSchedule changed = original.withHoliday(MON);

        assertThat(changed.isHoliday(MON)).isTrue();
        assertThat(original.isHoliday(MON)).isFalse();
    }

    @Test
    void generatedSlotsStartOnThirtyMinuteStepsFromTheRangeOpening() {
        List<TimeRange> day = List.of(range("08:15", "12:15"), range("14:00", "19:00"));
        BranchSchedule schedule = schedule(Set.of(), new Version(LocalDate.of(2026, 1, 1), Map.of(1, day)));

        assertThat(schedule.isGeneratedSlot(MON, LocalTime.of(8, 15))).isTrue();
        assertThat(schedule.isGeneratedSlot(MON, LocalTime.of(8, 45))).isTrue();
        assertThat(schedule.isGeneratedSlot(MON, LocalTime.of(11, 45))).isTrue();     // kết thúc đúng 12:15
        assertThat(schedule.isGeneratedSlot(MON, LocalTime.of(14, 0))).isTrue();      // bước tính lại ở khoảng thứ hai
        assertThat(schedule.isGeneratedSlot(MON, LocalTime.of(18, 30))).isTrue();
        assertThat(schedule.isGeneratedSlot(MON, LocalTime.of(8, 30))).isFalse();     // lệch bước so với 08:15
        assertThat(schedule.isGeneratedSlot(MON, LocalTime.of(9, 10))).isFalse();
        assertThat(schedule.isGeneratedSlot(MON, LocalTime.of(12, 0))).isFalse();     // vắt qua 12:15
        assertThat(schedule.isGeneratedSlot(MON, LocalTime.of(14, 15))).isFalse();
        assertThat(schedule.isGeneratedSlot(MON, LocalTime.of(18, 45))).isFalse();    // kết thúc sau 19:00
        assertThat(schedule.isGeneratedSlot(TUE, LocalTime.of(9, 0))).isFalse();      // thứ Ba không có giờ
    }

    @Test
    void noSlotIsGeneratedOnAHoliday() {
        BranchSchedule schedule = schedule(Set.of(MON), split());

        assertThat(schedule.isGeneratedSlot(MON, LocalTime.of(9, 0))).isFalse();
        assertThat(schedule.isGeneratedSlot(TUE, LocalTime.of(9, 0))).isTrue();
    }

    @Test
    void appointmentIsAffectedWhenItsSlotNoLongerFits() {
        BranchSchedule narrowed = schedule(Set.of(), split()).withVersion(
                new Version(LocalDate.of(2026, 10, 20), Map.of(
                        1, List.of(range("08:00", "12:00")), 2, List.of(range("08:00", "12:00")))));

        LocalDate monday = LocalDate.of(2026, 10, 26);
        assertThat(narrowed.appointmentAffected(monday, LocalTime.of(9, 0))).isFalse();
        assertThat(narrowed.appointmentAffected(monday, LocalTime.of(15, 0))).isTrue();       // buổi chiều bị bỏ
        assertThat(narrowed.appointmentAffected(LocalDate.of(2026, 10, 28), LocalTime.of(9, 0))).isTrue(); // thứ Tư nghỉ
        assertThat(narrowed.appointmentAffected(MON, LocalTime.of(15, 0))).isFalse();         // trước ngày hiệu lực
    }

    @Test
    void stayIsAffectedWhenCheckInOrCheckOutFallsOnAClosedDay() {
        BranchSchedule schedule = schedule(Set.of(), split());
        LocalDate previousSunday = LocalDate.of(2026, 10, 11);

        assertThat(schedule.stayAffected(MON, TUE, MON)).isFalse();
        assertThat(schedule.stayAffected(MON, SUN, MON)).isTrue();             // trả vào Chủ nhật nghỉ
        assertThat(schedule.stayAffected(previousSunday, TUE, previousSunday)).isTrue();   // nhận vào Chủ nhật nghỉ
        // Ngày nhận trước mốc xét thì bỏ qua, chỉ xét ngày trả.
        assertThat(schedule.stayAffected(previousSunday, TUE, MON)).isFalse();
        assertThat(schedule.stayAffected(previousSunday, SUN, MON)).isTrue();
    }

    @Test
    void stayIsAffectedByAHolidayOnItsCheckInOrCheckOutDate() {
        BranchSchedule schedule = schedule(Set.of(WED), split());

        assertThat(schedule.stayAffected(TUE, WED, MON)).isTrue();
        assertThat(schedule.stayAffected(WED, SAT, MON)).isTrue();
        assertThat(schedule.stayAffected(TUE, SAT, MON)).isFalse();
    }
}
