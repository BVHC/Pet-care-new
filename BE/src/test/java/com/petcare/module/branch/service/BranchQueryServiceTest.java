package com.petcare.module.branch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.branch.api.BranchQueryApi.TimeRange;
import com.petcare.module.branch.entity.Branch;
import com.petcare.module.branch.entity.BranchQuotaDefault;
import com.petcare.module.branch.entity.BranchQuotaDefaultId;
import com.petcare.module.branch.entity.BranchServiceSetting;
import com.petcare.module.branch.entity.BranchServiceSettingId;
import com.petcare.module.branch.entity.SlotQuota;
import com.petcare.module.branch.repository.BranchQuotaDefaultRepository;
import com.petcare.module.branch.repository.BranchServiceSettingRepository;
import com.petcare.module.branch.repository.SlotQuotaRepository;
import com.petcare.module.branch.repository.BranchRepository;
import com.petcare.module.branch.repository.HolidayRepository;
import com.petcare.module.branch.schedule.BranchSchedule;
import com.petcare.module.branch.schedule.BranchSchedule.Version;
import com.petcare.module.catalog.api.ServiceGroup;

/** {@code BranchQueryApi}: đọc cho identity, appointment, visit, sales, content (06 §2). Thứ Hai = 2026-10-12. */
@ExtendWith(MockitoExtension.class)
class BranchQueryServiceTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 12);
    private static final LocalDate TUE = LocalDate.of(2026, 10, 13);

    @Mock BranchRepository branches;
    @Mock HolidayRepository holidays;
    @Mock BranchServiceSettingRepository serviceSettings;
    @Mock BranchQuotaDefaultRepository quotaDefaults;
    @Mock SlotQuotaRepository slotQuotas;
    @Mock ScheduleLoader scheduleLoader;

    BranchQueryService service;

    @BeforeEach
    void setUp() {
        service = new BranchQueryService(branches, holidays, serviceSettings, quotaDefaults, slotQuotas, scheduleLoader);
    }

    private static BranchSchedule schedule() {
        List<TimeRange> day = List.of(new TimeRange(LocalTime.of(8, 0), LocalTime.of(12, 0)),
                new TimeRange(LocalTime.of(14, 0), LocalTime.of(19, 0)));
        return new BranchSchedule(List.of(new Version(LocalDate.of(2026, 1, 1), Map.of(1, day, 2, day))), Set.of());
    }

    private static Branch branch(String status) {
        Branch branch = new Branch("CN Quận 1", "Địa chỉ", "0281234567", BigDecimal.ONE, BigDecimal.ONE, true);
        ReflectionTestUtils.setField(branch, "id", 3L);
        ReflectionTestUtils.setField(branch, "status", com.petcare.module.branch.entity.BranchStatus.valueOf(status));
        return branch;
    }

    @Test
    void findBranchMapsStatusAndFlag() {
        when(branches.findById(3L)).thenReturn(Optional.of(branch("ACTIVE")));
        when(branches.findById(4L)).thenReturn(Optional.empty());

        BranchSummary summary = service.findBranch(3L).orElseThrow();

        assertThat(summary.branchId()).isEqualTo(3L);
        assertThat(summary.status()).isEqualTo(BranchStatus.ACTIVE);
        assertThat(summary.acceptsAfterHoursEmergency()).isTrue();
        assertThat(service.findBranch(4L)).isEmpty();
    }

    @Test
    void listActiveBranchesReturnsSummaries() {
        when(branches.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of(branch("ACTIVE")));

        assertThat(service.listActiveBranches()).extracting(BranchSummary::name).containsExactly("CN Quận 1");
    }

    @Test
    void scheduleQueriesDelegateToTheLoadedSchedule() {
        when(scheduleLoader.load(3L)).thenReturn(schedule());

        assertThat(service.openingRangesOn(3L, MON)).hasSize(2);
        assertThat(service.openingRangesOn(3L, LocalDate.of(2026, 10, 14))).isEmpty();
        assertThat(service.isOpenAt(3L, MON.atTime(9, 0))).isTrue();
        assertThat(service.isOpenAt(3L, MON.atTime(12, 0))).isFalse();
        assertThat(service.nextOpeningStart(3L, MON.atTime(19, 0))).contains(TUE.atTime(8, 0));
        assertThat(service.nextOpeningStart(3L, TUE.atTime(19, 0)))
                .contains(LocalDateTime.of(2026, 10, 19, 8, 0));
    }

    @Test
    void isHolidayUsesTheDirectLookupWithoutLoadingTheSchedule() {
        when(holidays.existsByBranchIdAndHolidayDate(3L, MON)).thenReturn(true);

        assertThat(service.isHoliday(3L, MON)).isTrue();
        assertThat(service.isHoliday(3L, TUE)).isFalse();
        verifyNoInteractions(scheduleLoader);
    }

    @Test
    void serviceEnablementIsReadFromBranchServices() {
        when(serviceSettings.findById(new BranchServiceSettingId(3L, 7L)))
                .thenReturn(Optional.of(new BranchServiceSetting(3L, 7L, true)));
        when(serviceSettings.findById(new BranchServiceSettingId(3L, 8L))).thenReturn(Optional.empty());
        when(serviceSettings.findById(new BranchServiceSettingId(3L, 9L)))
                .thenReturn(Optional.of(new BranchServiceSetting(3L, 9L, false)));
        when(serviceSettings.findActiveBranchIdsEnabling(7L)).thenReturn(List.of(3L, 4L));

        assertThat(service.isServiceEnabled(3L, 7L)).isTrue();
        assertThat(service.isServiceEnabled(3L, 8L)).isFalse();     // chưa có dòng = chưa bật
        assertThat(service.isServiceEnabled(3L, 9L)).isFalse();     // đã tắt
        assertThat(service.findActiveBranchIdsEnablingService(7L)).containsExactly(3L, 4L);
    }

    @Test
    void quotaPrefersTheSlotThenTheDefaultThenOne_BR_LH_03() {
        LocalTime nine = LocalTime.of(9, 0);
        when(slotQuotas.findByBranchIdAndServiceGroupAndSlotDateAndSlotStart(3L, ServiceGroup.MEDICAL, MON, nine))
                .thenReturn(Optional.of(new SlotQuota(3L, ServiceGroup.MEDICAL, MON, nine, 0, 1L)));
        when(slotQuotas.findByBranchIdAndServiceGroupAndSlotDateAndSlotStart(3L, ServiceGroup.GROOMING, MON, nine))
                .thenReturn(Optional.empty());
        when(quotaDefaults.findById(new BranchQuotaDefaultId(3L, ServiceGroup.GROOMING)))
                .thenReturn(Optional.of(new BranchQuotaDefault(3L, ServiceGroup.GROOMING, 4, 1L)));
        when(slotQuotas.findByBranchIdAndServiceGroupAndSlotDateAndSlotStart(3L, ServiceGroup.MEDICAL, TUE, nine))
                .thenReturn(Optional.empty());
        when(quotaDefaults.findById(new BranchQuotaDefaultId(3L, ServiceGroup.MEDICAL))).thenReturn(Optional.empty());

        assertThat(service.quotaFor(3L, ServiceGroup.MEDICAL, MON, nine)).isZero();      // 0 = khóa khung, vẫn thắng
        assertThat(service.quotaFor(3L, ServiceGroup.GROOMING, MON, nine)).isEqualTo(4);
        assertThat(service.quotaFor(3L, ServiceGroup.MEDICAL, TUE, nine)).isEqualTo(1);
    }

    @Test
    void boardingHasNoSlotQuota() {
        assertThatThrownBy(() -> service.quotaFor(3L, ServiceGroup.BOARDING, MON, LocalTime.of(9, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(slotQuotas, quotaDefaults);
    }
}
