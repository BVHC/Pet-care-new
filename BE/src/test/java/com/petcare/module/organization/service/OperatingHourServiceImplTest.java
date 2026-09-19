package com.petcare.module.organization.service;

import com.petcare.module.organization.dto.ConfigureOperatingHoursRequest;
import com.petcare.module.organization.dto.OperatingHourItem;
import com.petcare.module.organization.entity.OperatingHour;
import com.petcare.module.organization.entity.Store;
import com.petcare.module.organization.mapper.OperatingHourMapper;
import com.petcare.module.organization.mapper.OperatingHourMapperImpl;
import com.petcare.module.organization.repository.OperatingHourRepository;
import com.petcare.module.organization.repository.StoreRepository;
import com.petcare.platform.enums.FacilityType;
import com.petcare.platform.enums.StoreStatus;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** docs/02-business-rules.md RULE-03-02/06/07 (ConfigureOperatingHour). */
@ExtendWith(MockitoExtension.class)
class OperatingHourServiceImplTest {

    @Mock
    private StoreRepository storeRepository;
    @Mock
    private OperatingHourRepository operatingHourRepository;

    private final OperatingHourMapper operatingHourMapper = new OperatingHourMapperImpl();

    private OperatingHourServiceImpl service;

    private static UserPrincipal principal(UserRole role, UUID organizationId, UUID storeId) {
        return UserPrincipal.builder().userId(UUID.randomUUID()).role(role).organizationId(organizationId)
                .storeId(storeId).build();
    }

    private static Store store(UUID id, StoreStatus status) {
        Store store = new Store(UUID.randomUUID(), "HN01", "Chi nhanh Q1", FacilityType.RETAIL_STORE,
                "123 Le Loi", "0901234567");
        store.setId(id);
        store.setStatus(status);
        return store;
    }

    private static OperatingHourItem openDay(int dayOfWeek, String open, String close) {
        return new OperatingHourItem(dayOfWeek, LocalTime.parse(open), LocalTime.parse(close), false);
    }

    private static OperatingHourItem closedDay(int dayOfWeek) {
        return new OperatingHourItem(dayOfWeek, null, null, true);
    }

    @BeforeEach
    void setUp() {
        service = new OperatingHourServiceImpl(storeRepository, operatingHourRepository, operatingHourMapper);
    }

    @Test
    void configureOperatingHours_storeNotFound_throwsResourceNotFound() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.configureOperatingHours(storeId,
                new ConfigureOperatingHoursRequest(List.of(openDay(2, "08:00", "17:00"))),
                principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void configureOperatingHours_notOwnStore_deniedByScope() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.DRAFT)));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> service.configureOperatingHours(storeId,
                new ConfigureOperatingHoursRequest(List.of(openDay(2, "08:00", "17:00"))), actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void configureOperatingHours_organizationAdmin_deniedRegardlessOfScope() {
        // Khác UpdateStore — OrgAdmin KHÔNG được gọi endpoint này dù quản lý đúng Org chứa Store.
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.DRAFT)));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID(), null);

        assertThatThrownBy(() -> service.configureOperatingHours(storeId,
                new ConfigureOperatingHoursRequest(List.of(openDay(2, "08:00", "17:00"))), actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void configureOperatingHours_archivedStore_throwsBusinessRuleViolation_RULE_03_06() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.ARCHIVED)));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId);

        assertThatThrownBy(() -> service.configureOperatingHours(storeId,
                new ConfigureOperatingHoursRequest(List.of(openDay(2, "08:00", "17:00"))), actor))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-03-06"));
        verify(operatingHourRepository, never()).deleteAllByStoreId(any());
    }

    @Test
    void configureOperatingHours_duplicateDayOfWeek_throwsBusinessRuleViolation_RULE_03_07() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.DRAFT)));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId);
        var request = new ConfigureOperatingHoursRequest(
                List.of(openDay(2, "08:00", "17:00"), openDay(2, "09:00", "18:00")));

        assertThatThrownBy(() -> service.configureOperatingHours(storeId, request, actor))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-03-07"));
        verify(operatingHourRepository, never()).deleteAllByStoreId(any());
    }

    @Test
    void configureOperatingHours_openDayMissingCloseTime_throwsBusinessRuleViolation_RULE_03_07() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.DRAFT)));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId);
        var request = new ConfigureOperatingHoursRequest(
                List.of(new OperatingHourItem(2, LocalTime.parse("08:00"), null, false)));

        assertThatThrownBy(() -> service.configureOperatingHours(storeId, request, actor))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-03-07"));
    }

    @Test
    void configureOperatingHours_openTimeAfterCloseTime_throwsBusinessRuleViolation_RULE_03_07() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.DRAFT)));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId);
        var request = new ConfigureOperatingHoursRequest(List.of(openDay(2, "18:00", "08:00")));

        assertThatThrownBy(() -> service.configureOperatingHours(storeId, request, actor))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-03-07"));
    }

    @Test
    void configureOperatingHours_closedDayWithTimes_throwsBusinessRuleViolation_RULE_03_07() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.DRAFT)));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId);
        var request = new ConfigureOperatingHoursRequest(
                List.of(new OperatingHourItem(2, LocalTime.parse("08:00"), LocalTime.parse("17:00"), true)));

        assertThatThrownBy(() -> service.configureOperatingHours(storeId, request, actor))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-03-07"));
    }

    @Test
    void configureOperatingHours_valid_replacesAllAndReturnsResponseSortedByDayOfWeek() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.DRAFT)));
        when(operatingHourRepository.saveAll(any())).thenAnswer(invocation -> {
            List<OperatingHour> entities = invocation.getArgument(0);
            entities.forEach(e -> e.setId(UUID.randomUUID()));
            return entities;
        });
        // Đọc lại theo dayOfWeek (mô phỏng DB trả về đã sort) — request gửi day 2 trước day 1,
        // response phải theo đúng thứ tự dayOfWeek (1 rồi 2), không theo thứ tự request.
        when(operatingHourRepository.findAllByStoreIdOrderByDayOfWeek(storeId)).thenReturn(List.of(
                new OperatingHour(storeId, 1, null, null, true),
                new OperatingHour(storeId, 2, LocalTime.parse("08:00"), LocalTime.parse("17:00"), false)));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId);
        var request = new ConfigureOperatingHoursRequest(List.of(
                openDay(2, "08:00", "17:00"),
                closedDay(1)));

        var response = service.configureOperatingHours(storeId, request, actor);

        verify(operatingHourRepository).deleteAllByStoreId(storeId);
        assertThat(response.storeId()).isEqualTo(storeId);
        assertThat(response.hours()).hasSize(2);
        assertThat(response.hours().get(0).dayOfWeek()).isEqualTo(1);
        assertThat(response.hours().get(0).isClosed()).isTrue();
        assertThat(response.hours().get(1).dayOfWeek()).isEqualTo(2);
    }

    @Test
    void configureOperatingHours_concurrentWrite_throwsBusinessRuleViolation_RULE_03_07() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.DRAFT)));
        when(operatingHourRepository.saveAll(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_operating_hours_store_day"));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId);
        var request = new ConfigureOperatingHoursRequest(List.of(openDay(2, "08:00", "17:00")));

        assertThatThrownBy(() -> service.configureOperatingHours(storeId, request, actor))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-03-07"));
    }
}
