package com.petcare.module.branch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;

import com.petcare.module.branch.api.BranchQueryApi.TimeRange;
import com.petcare.module.branch.dto.DayHoursDto;
import com.petcare.module.branch.dto.OpeningHoursVersionResponse;
import com.petcare.module.branch.dto.TimeRangeDto;
import com.petcare.module.branch.entity.OpeningHours;
import com.petcare.module.branch.schedule.BranchSchedule.Version;
import com.petcare.platform.exception.BusinessRuleViolationException;

/** BR-CN-02: tối đa 2 khoảng giờ mỗi ngày, không chồng nhau, giờ mở trước giờ đóng, đủ 7 ngày. */
class OpeningHoursMappingTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 20);

    private static TimeRangeDto range(String open, String close) {
        return new TimeRangeDto(LocalTime.parse(open), LocalTime.parse(close));
    }

    private static DayHoursDto day(int dayOfWeek, TimeRangeDto... ranges) {
        return new DayHoursDto(dayOfWeek, List.of(ranges));
    }

    private static List<DayHoursDto> week(IntFunction<DayHoursDto> perDay) {
        List<DayHoursDto> days = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            days.add(perDay.apply(d));
        }
        return days;
    }

    private static List<DayHoursDto> weekWith(DayHoursDto monday) {
        return week(d -> d == 1 ? monday : day(d));
    }

    private static void assertRule(Throwable thrown) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.getRuleId()).isEqualTo("BR-CN-02"));
    }

    @Test
    void acceptsAFullWeekWithZeroOneAndTwoRanges() {
        List<DayHoursDto> days = week(d -> switch (d) {
            case 1 -> day(d, range("08:00", "12:00"), range("14:00", "19:00"));
            case 2 -> day(d, range("08:00", "17:00"));
            default -> day(d);
        });

        Version version = OpeningHoursMapping.fromRequest(FROM, days);

        assertThat(version.effectiveFrom()).isEqualTo(FROM);
        assertThat(version.days().get(1)).hasSize(2);
        assertThat(version.days().get(2)).hasSize(1);
        assertThat(version.days().get(7)).isEmpty();
    }

    @Test
    void sortsRangesGivenOutOfOrder() {
        Version version = OpeningHoursMapping.fromRequest(FROM,
                weekWith(day(1, range("14:00", "19:00"), range("08:00", "12:00"))));

        assertThat(version.days().get(1)).containsExactly(
                new TimeRange(LocalTime.of(8, 0), LocalTime.of(12, 0)),
                new TimeRange(LocalTime.of(14, 0), LocalTime.of(19, 0)));
    }

    @Test
    void allowsTouchingRanges() {
        Version version = OpeningHoursMapping.fromRequest(FROM,
                weekWith(day(1, range("08:00", "12:00"), range("12:00", "14:00"))));

        assertThat(version.days().get(1)).hasSize(2);
    }

    @Test
    void rejectsMoreThanTwoRanges() {
        assertThatThrownBy(() -> OpeningHoursMapping.fromRequest(FROM, weekWith(
                day(1, range("08:00", "10:00"), range("11:00", "13:00"), range("14:00", "16:00")))))
                .satisfies(OpeningHoursMappingTest::assertRule);
    }

    @Test
    void rejectsOpeningNotBeforeClosing() {
        assertThatThrownBy(() -> OpeningHoursMapping.fromRequest(FROM, weekWith(day(1, range("12:00", "12:00")))))
                .satisfies(OpeningHoursMappingTest::assertRule);
        assertThatThrownBy(() -> OpeningHoursMapping.fromRequest(FROM, weekWith(day(1, range("19:00", "08:00")))))
                .satisfies(OpeningHoursMappingTest::assertRule);
    }

    @Test
    void rejectsOverlappingRanges() {
        assertThatThrownBy(() -> OpeningHoursMapping.fromRequest(FROM,
                weekWith(day(1, range("08:00", "13:00"), range("12:59", "19:00")))))
                .satisfies(OpeningHoursMappingTest::assertRule);
        assertThatThrownBy(() -> OpeningHoursMapping.fromRequest(FROM,
                weekWith(day(1, range("08:00", "19:00"), range("10:00", "12:00")))))
                .satisfies(OpeningHoursMappingTest::assertRule);
    }

    @Test
    void requiresExactlySevenDistinctDaysOneToSeven() {
        assertThatThrownBy(() -> OpeningHoursMapping.fromRequest(FROM, List.of(day(1), day(2))))
                .satisfies(OpeningHoursMappingTest::assertRule);
        assertThatThrownBy(() -> OpeningHoursMapping.fromRequest(FROM, week(d -> day(d == 7 ? 1 : d))))
                .satisfies(OpeningHoursMappingTest::assertRule);
        assertThatThrownBy(() -> OpeningHoursMapping.fromRequest(FROM, week(d -> day(d == 7 ? 8 : d))))
                .satisfies(OpeningHoursMappingTest::assertRule);
        assertThatThrownBy(() -> OpeningHoursMapping.fromRequest(FROM, week(d -> day(d == 7 ? 0 : d))))
                .satisfies(OpeningHoursMappingTest::assertRule);
    }

    @Test
    void rowsRoundTripThroughAVersion() {
        Version version = OpeningHoursMapping.fromRequest(FROM, week(d -> switch (d) {
            case 1 -> day(d, range("08:00", "12:00"), range("14:00", "19:00"));
            case 2 -> day(d, range("09:00", "17:00"));
            default -> day(d);
        }));

        List<OpeningHours> rows = OpeningHoursMapping.toRows(5L, version);

        assertThat(rows).hasSize(7);
        assertThat(rows).extracting(OpeningHours::getDayOfWeek).containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(rows.get(0).getOpen2()).isEqualTo(LocalTime.of(14, 0));
        assertThat(rows.get(1).getOpen2()).isNull();
        assertThat(rows.get(2).getOpen1()).isNull();
        assertThat(rows).allSatisfy(r -> assertThat(r.getBranchId()).isEqualTo(5L));
        assertThat(OpeningHoursMapping.toVersion(FROM, rows)).isEqualTo(version);
    }

    @Test
    void responseListsAllSevenDaysEvenWhenOff() {
        Version version = OpeningHoursMapping.fromRequest(FROM, weekWith(day(1, range("08:00", "12:00"))));

        OpeningHoursVersionResponse response = OpeningHoursMapping.toResponse(version);

        assertThat(response.effectiveFrom()).isEqualTo(FROM);
        assertThat(response.days()).hasSize(7);
        assertThat(response.days().get(0).ranges()).hasSize(1);
        assertThat(response.days().get(6).ranges()).isEmpty();
    }
}
