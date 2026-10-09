package com.petcare.module.branch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.branch.api.BranchQueryApi.TimeRange;
import com.petcare.module.branch.dto.QuotaDefaultResponse;
import com.petcare.module.branch.dto.SetSlotQuotaRequest;
import com.petcare.module.branch.dto.SlotQuotaResponse;
import com.petcare.module.branch.entity.Branch;
import com.petcare.module.branch.entity.BranchQuotaDefault;
import com.petcare.module.branch.entity.BranchQuotaDefaultId;
import com.petcare.module.branch.entity.SlotQuota;
import com.petcare.module.branch.repository.BranchQuotaDefaultRepository;
import com.petcare.module.branch.repository.BranchRepository;
import com.petcare.module.branch.repository.SlotQuotaRepository;
import com.petcare.module.branch.schedule.BranchSchedule;
import com.petcare.module.branch.schedule.BranchSchedule.Version;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/** UC42: BR-LH-03 (quota mặc định và quota riêng, 0 = khóa khung), BR-LH-02 (khung phải hợp lệ). */
@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    /** Thứ Hai 2026-10-12: mở 08:15–12:15 và 14:00–19:00; Chủ nhật 2026-10-18 nghỉ. */
    private static final LocalDate MON = LocalDate.of(2026, 10, 12);
    private static final LocalDate SUN = LocalDate.of(2026, 10, 18);

    @Mock BranchRepository branches;
    @Mock BranchQuotaDefaultRepository defaults;
    @Mock SlotQuotaRepository slotQuotas;
    @Mock ScheduleLoader scheduleLoader;
    @Mock BranchScope scope;

    QuotaService service;

    @BeforeEach
    void setUp() {
        service = new QuotaService(branches, defaults, slotQuotas, scheduleLoader, scope);
    }

    private Branch branch() {
        Branch branch = new Branch("CN", "Địa chỉ", "0281234567", BigDecimal.ONE, BigDecimal.ONE, false);
        ReflectionTestUtils.setField(branch, "id", 3L);
        return branch;
    }

    private void actor() {
        SecurityPrincipal principal = mock(SecurityPrincipal.class);
        when(principal.accountId()).thenReturn(9L);
        when(scope.current()).thenReturn(principal);
    }

    private void schedule() {
        List<TimeRange> day = List.of(new TimeRange(LocalTime.of(8, 15), LocalTime.of(12, 15)),
                new TimeRange(LocalTime.of(14, 0), LocalTime.of(19, 0)));
        when(scheduleLoader.load(3L)).thenReturn(new BranchSchedule(
                List.of(new Version(LocalDate.of(2026, 1, 1), Map.of(1, day))), Set.of()));
    }

    private static void assertRule(Throwable thrown, String ruleId) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.getRuleId()).isEqualTo(ruleId));
    }

    // -------------------------------------------------------------- quota mặc định

    @Test
    void listsBothGroupsAndLeavesTheUnconfiguredOneNull() {
        when(branches.findById(3L)).thenReturn(Optional.of(branch()));
        when(defaults.findByBranchId(3L)).thenReturn(List.of(new BranchQuotaDefault(3L, ServiceGroup.GROOMING, 4, 9L)));

        List<QuotaDefaultResponse> response = service.listDefaults(3L);

        assertThat(response).extracting(QuotaDefaultResponse::serviceGroup)
                .containsExactly(ServiceGroup.MEDICAL, ServiceGroup.GROOMING);
        assertThat(response).extracting(QuotaDefaultResponse::defaultQuota).containsExactly(null, 4);
        verify(scope).check(3L);
    }

    @Test
    void createsADefaultQuotaAndRecordsTheActor() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch()));
        actor();
        when(defaults.findById(new BranchQuotaDefaultId(3L, ServiceGroup.MEDICAL))).thenReturn(Optional.empty());

        QuotaDefaultResponse response = service.setDefault(3L, ServiceGroup.MEDICAL, 3);

        assertThat(response.defaultQuota()).isEqualTo(3);
        ArgumentCaptor<BranchQuotaDefault> captor = ArgumentCaptor.forClass(BranchQuotaDefault.class);
        verify(defaults).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getDefaultQuota()).isEqualTo(3);
        assertThat(captor.getValue().getUpdatedBy()).isEqualTo(9L);
    }

    @Test
    void overwritesAnExistingDefaultQuota() {
        BranchQuotaDefault existing = new BranchQuotaDefault(3L, ServiceGroup.MEDICAL, 5, 1L);
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch()));
        actor();
        when(defaults.findById(new BranchQuotaDefaultId(3L, ServiceGroup.MEDICAL))).thenReturn(Optional.of(existing));

        service.setDefault(3L, ServiceGroup.MEDICAL, 0);       // giảm hoặc về 0 không bị chặn

        assertThat(existing.getDefaultQuota()).isZero();
        assertThat(existing.getUpdatedBy()).isEqualTo(9L);
        verify(defaults).saveAndFlush(existing);
    }

    @Test
    void boardingHasNoQuota_BR_LH_03() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch()));

        assertThatThrownBy(() -> service.setDefault(3L, ServiceGroup.BOARDING, 2))
                .satisfies(t -> assertRule(t, "BR-LH-03"));
        verify(defaults, never()).saveAndFlush(any());
    }

    @Test
    void quotaMustFitASmallint_BR_LH_03() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch()));

        assertThatThrownBy(() -> service.setDefault(3L, ServiceGroup.MEDICAL, -1))
                .satisfies(t -> assertRule(t, "BR-LH-03"));
        assertThatThrownBy(() -> service.setDefault(3L, ServiceGroup.MEDICAL, 32_768))
                .satisfies(t -> assertRule(t, "BR-LH-03"));
        verify(defaults, never()).saveAndFlush(any());
    }

    @Test
    void acceptsTheLargestSmallint() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch()));
        actor();
        when(defaults.findById(any(BranchQuotaDefaultId.class))).thenReturn(Optional.empty());

        assertThat(service.setDefault(3L, ServiceGroup.GROOMING, 32_767).defaultQuota()).isEqualTo(32_767);
    }

    // -------------------------------------------------------------- quota riêng

    @Test
    void createsASlotQuotaOnAGeneratedSlot() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch()));
        actor();
        schedule();
        LocalTime start = LocalTime.of(8, 45);          // 08:15 + 30 phút
        when(slotQuotas.findByBranchIdAndServiceGroupAndSlotDateAndSlotStart(3L, ServiceGroup.MEDICAL, MON, start))
                .thenReturn(Optional.empty());
        when(slotQuotas.saveAndFlush(any(SlotQuota.class))).thenAnswer(inv -> inv.getArgument(0));

        SlotQuotaResponse response = service.setSlotQuota(3L,
                new SetSlotQuotaRequest(ServiceGroup.MEDICAL, MON, start, 0));

        assertThat(response.quota()).isZero();          // 0 = khóa khung
        assertThat(response.slotStart()).isEqualTo(start);
        ArgumentCaptor<SlotQuota> captor = ArgumentCaptor.forClass(SlotQuota.class);
        verify(slotQuotas).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUpdatedBy()).isEqualTo(9L);
        assertThat(captor.getValue().getBranchId()).isEqualTo(3L);
    }

    @Test
    void overwritesTheSlotQuotaOfTheSameSlot() {
        SlotQuota existing = new SlotQuota(3L, ServiceGroup.GROOMING, MON, LocalTime.of(14, 0), 2, 1L);
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch()));
        actor();
        schedule();
        when(slotQuotas.findByBranchIdAndServiceGroupAndSlotDateAndSlotStart(3L, ServiceGroup.GROOMING, MON,
                LocalTime.of(14, 0))).thenReturn(Optional.of(existing));
        when(slotQuotas.saveAndFlush(existing)).thenReturn(existing);

        service.setSlotQuota(3L, new SetSlotQuotaRequest(ServiceGroup.GROOMING, MON, LocalTime.of(14, 0), 6));

        assertThat(existing.getQuota()).isEqualTo(6);
        assertThat(existing.getUpdatedBy()).isEqualTo(9L);
    }

    @Test
    void rejectsSlotsThatTheAppointmentModuleWouldNeverGenerate_BR_LH_02() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch()));
        schedule();
        LocalTime[] bad = {LocalTime.of(8, 0),      // trước giờ mở
                LocalTime.of(8, 30),                // lệch bước 30 phút so với 08:15
                LocalTime.of(12, 0),                // vắt qua giờ đóng 12:15
                LocalTime.of(13, 0),                // giờ nghỉ giữa ca
                LocalTime.of(18, 45)};              // kết thúc sau 19:00
        for (LocalTime start : bad) {
            assertThatThrownBy(() -> service.setSlotQuota(3L,
                    new SetSlotQuotaRequest(ServiceGroup.MEDICAL, MON, start, 1)))
                    .as("start %s", start).satisfies(t -> assertRule(t, "BR-LH-02"));
        }
        assertThatThrownBy(() -> service.setSlotQuota(3L,
                new SetSlotQuotaRequest(ServiceGroup.MEDICAL, SUN, LocalTime.of(9, 0), 1)))
                .satisfies(t -> assertRule(t, "BR-LH-02"));          // Chủ nhật nghỉ cố định
        verify(slotQuotas, never()).saveAndFlush(any());
    }

    @Test
    void slotQuotaValidatesGroupAndValueBeforeLoadingTheSchedule() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch()));

        assertThatThrownBy(() -> service.setSlotQuota(3L,
                new SetSlotQuotaRequest(ServiceGroup.BOARDING, MON, LocalTime.of(9, 0), 1)))
                .satisfies(t -> assertRule(t, "BR-LH-03"));
        assertThatThrownBy(() -> service.setSlotQuota(3L,
                new SetSlotQuotaRequest(ServiceGroup.MEDICAL, MON, LocalTime.of(9, 0), -1)))
                .satisfies(t -> assertRule(t, "BR-LH-03"));
        verifyNoInteractions(scheduleLoader);
    }

    @Test
    void setOnUnknownBranchIsNotFound() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setSlotQuota(3L,
                new SetSlotQuotaRequest(ServiceGroup.MEDICAL, MON, LocalTime.of(9, 0), 1)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.setDefault(3L, ServiceGroup.MEDICAL, 1))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // -------------------------------------------------------------- xem, xóa quota riêng

    @Test
    void listsSlotQuotasInTheRangeWithOrWithoutAGroup() {
        when(branches.findById(3L)).thenReturn(Optional.of(branch()));
        SlotQuota quota = new SlotQuota(3L, ServiceGroup.MEDICAL, MON, LocalTime.of(9, 0), 2, 9L);
        ReflectionTestUtils.setField(quota, "id", 11L);
        when(slotQuotas.findByBranchIdAndSlotDateBetweenOrderBySlotDateAscSlotStartAscServiceGroupAsc(3L, MON, SUN))
                .thenReturn(List.of(quota));
        when(slotQuotas.findByBranchIdAndServiceGroupAndSlotDateBetweenOrderBySlotDateAscSlotStartAsc(3L,
                ServiceGroup.GROOMING, MON, SUN)).thenReturn(List.of());

        assertThat(service.listSlotQuotas(3L, MON, SUN, null)).extracting(SlotQuotaResponse::slotQuotaId)
                .containsExactly(11L);
        assertThat(service.listSlotQuotas(3L, MON, SUN, ServiceGroup.GROOMING)).isEmpty();
    }

    @Test
    void listRejectsAReversedRangeAndTheBoardingGroup() {
        when(branches.findById(3L)).thenReturn(Optional.of(branch()));

        assertThatThrownBy(() -> service.listSlotQuotas(3L, SUN, MON, null)).satisfies(t -> assertRule(t, "BR-LH-03"));
        assertThatThrownBy(() -> service.listSlotQuotas(3L, MON, SUN, ServiceGroup.BOARDING))
                .satisfies(t -> assertRule(t, "BR-LH-03"));
    }

    @Test
    void deletesTheSlotQuotaOfThisBranchOnly() {
        SlotQuota quota = new SlotQuota(3L, ServiceGroup.MEDICAL, MON, LocalTime.of(9, 0), 2, 9L);
        when(branches.findById(3L)).thenReturn(Optional.of(branch()));
        when(slotQuotas.findByIdAndBranchId(11L, 3L)).thenReturn(Optional.of(quota));
        when(slotQuotas.findByIdAndBranchId(12L, 3L)).thenReturn(Optional.empty());

        service.deleteSlotQuota(3L, 11L);

        verify(slotQuotas).delete(quota);
        assertThatThrownBy(() -> service.deleteSlotQuota(3L, 12L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
