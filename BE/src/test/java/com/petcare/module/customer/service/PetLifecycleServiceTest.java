package com.petcare.module.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.customer.api.PetDeceasedEvent;
import com.petcare.module.customer.api.PetOwnerTransferredEvent;
import com.petcare.module.customer.api.Species;
import com.petcare.module.customer.dto.MarkDeceasedRequest;
import com.petcare.module.customer.dto.PetResponse;
import com.petcare.module.customer.dto.TransferOwnerRequest;
import com.petcare.module.customer.entity.Customer;
import com.petcare.module.customer.entity.Pet;
import com.petcare.module.customer.entity.PetSex;
import com.petcare.module.customer.repository.CustomerRepository;
import com.petcare.module.customer.repository.PetRepository;
import com.petcare.module.customer.repository.WeightRecordRepository;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Đánh dấu đã mất, xóa, chuyển chủ (docs/adr/0041): khóa trước đọc sau, mọi lần từ chối trước lệnh ghi đầu tiên, sự kiện
 * và audit chỉ sau khi đã ghi và flush.
 */
class PetLifecycleServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    private final PetRepository pets = mock(PetRepository.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final WeightRecordRepository weights = mock(WeightRecordRepository.class);
    private final PetAccess access = mock(PetAccess.class);
    private final PetViews views = mock(PetViews.class);
    private final VisitQueryApi visits = mock(VisitQueryApi.class);
    private final BoardingQueryApi boardings = mock(BoardingQueryApi.class);
    private final OrderQueryApi orders = mock(OrderQueryApi.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final PetLifecycleService service = new PetLifecycleService(pets, customers, weights, access, views,
            visits, boardings, orders, events, audit, Clock.fixed(Instant.parse("2026-10-16T03:00:00Z"),
                    ZoneOffset.UTC));

    private Pet pet;

    @BeforeEach
    void setUp() {
        pet = Pet.create(7L, "Mực", Species.DOG, PetSex.MALE, null, LocalDate.of(2024, 3, 15), false, null, null, null);
        ReflectionTestUtils.setField(pet, "id", 1L);
        when(access.viewer()).thenReturn(new PetAccess.Viewer(null));
        SecurityPrincipal principal = mock(SecurityPrincipal.class);
        when(principal.accountId()).thenReturn(42L);
        when(access.current()).thenReturn(principal);
        when(pets.findByIdForUpdate(1L)).thenReturn(Optional.of(pet));
        when(views.view(pet)).thenReturn(mock(PetResponse.class));
    }

    private static MarkDeceasedRequest deceasedOn(LocalDate date) {
        return new MarkDeceasedRequest(date, true);
    }

    // ---------------------------------------------------------------- đánh dấu đã mất

    @Test
    void markDeceasedLocksThenChecksThenWritesThenPublishesTheEvent() {
        PetResponse result = service.markDeceased(1L, deceasedOn(LocalDate.of(2026, 9, 30)));

        assertThat(result).isNotNull();
        assertThat(pet.getDeceasedOn()).isEqualTo(LocalDate.of(2026, 9, 30));
        InOrder order = inOrder(access, pets, visits, boardings, events);
        order.verify(pets).findByIdForUpdate(1L);
        order.verify(access).requireVisible(any(), eq(pet));
        order.verify(visits).hasOpenVisit(1L);
        order.verify(boardings).isPetInStay(1L);
        order.verify(pets).flush();
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        order.verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new PetDeceasedEvent(1L, 7L, LocalDate.of(2026, 9, 30), 42L));
        verifyNoInteractions(audit);
    }

    @Test
    void todayIsAcceptedAndTheDateMustNotPrecedeBirth() {
        service.markDeceased(1L, deceasedOn(TODAY));
        assertThat(pet.isDeceased()).isTrue();
    }

    @Test
    void futureDateOrBeforeBirthIsBrKh05AndNothingIsPublished() {
        assertThatThrownBy(() -> service.markDeceased(1L, deceasedOn(TODAY.plusDays(1))))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KH-05");
        assertThatThrownBy(() -> service.markDeceased(1L, deceasedOn(LocalDate.of(2024, 3, 14))))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KH-05");
        assertThat(pet.isDeceased()).isFalse();
        verify(pets, never()).flush();
        verifyNoInteractions(events, visits, boardings);
    }

    @Test
    void openVisitAndStayAreBothNamedInOneMessage() {
        when(visits.hasOpenVisit(1L)).thenReturn(true);
        when(boardings.isPetInStay(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.markDeceased(1L, deceasedOn(TODAY)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KH-05")
                .hasMessageContaining("lượt khám").hasMessageContaining("BR-LT-12");
        assertThat(pet.isDeceased()).isFalse();
        verify(pets, never()).flush();
        verifyNoInteractions(events);
    }

    @Test
    void secondMarkIs409AndDoesNotPublishAgain() {
        pet.markDeceased(LocalDate.of(2026, 9, 1));

        assertThatThrownBy(() -> service.markDeceased(1L, deceasedOn(TODAY)))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThat(pet.getDeceasedOn()).isEqualTo(LocalDate.of(2026, 9, 1));
        verifyNoInteractions(events, visits, boardings);
    }

    @Test
    void markOfAMissingOrForeignPetIs404BeforeAnyCrossModuleCall() {
        when(pets.findByIdForUpdate(2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.markDeceased(2L, deceasedOn(TODAY)))
                .isInstanceOf(ResourceNotFoundException.class);

        org.mockito.Mockito.doThrow(new ResourceNotFoundException("thú cưng", 1L)).when(access)
                .requireVisible(any(), eq(pet));
        assertThatThrownBy(() -> service.markDeceased(1L, deceasedOn(TODAY)))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(visits, boardings, events);
    }

    // ---------------------------------------------------------------- xóa

    @Test
    void deleteRemovesWeightsThenThePet() {
        service.deletePet(1L);

        InOrder order = inOrder(pets, views, weights);
        order.verify(pets).findByIdForUpdate(1L);
        order.verify(views).hasTransactions(1L);
        order.verify(weights).deleteByPet(1L);
        order.verify(pets).delete(pet);
        order.verify(pets).flush();
    }

    @Test
    void deleteIsRefusedWhenThereIsAnyTransaction() {
        when(views.hasTransactions(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.deletePet(1L)).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("BR-KH-06");
        verify(weights, never()).deleteByPet(any());
        verify(pets, never()).delete(any());
    }

    @Test
    void deleteOfADeceasedPetIsBrKh05() {
        pet.markDeceased(LocalDate.of(2026, 9, 1));

        assertThatThrownBy(() -> service.deletePet(1L)).hasMessageContaining("BR-KH-05");
        verify(views, never()).hasTransactions(any());
        verify(pets, never()).delete(any());
    }

    // ---------------------------------------------------------------- chuyển chủ

    private void stubTransferTargets() {
        when(pets.findCustomerIdById(1L)).thenReturn(Optional.of(7L));
        when(customers.findByIdForUpdate(any())).thenAnswer(call -> Optional.of(mock(Customer.class)));
    }

    @Test
    void transferLocksCustomersAscendingThenThePetThenWritesEventAndAudit() {
        stubTransferTargets();

        service.transferOwner(1L, new TransferOwnerRequest(3L, true));

        InOrder order = inOrder(pets, customers, visits, orders, boardings, events, audit);
        order.verify(pets).findCustomerIdById(1L);
        order.verify(customers).findByIdForUpdate(3L);
        order.verify(customers).findByIdForUpdate(7L);
        order.verify(pets).findByIdForUpdate(1L);
        order.verify(visits).hasOpenVisit(1L);
        order.verify(orders).hasPendingOrderForPet(1L);
        order.verify(boardings).isPetInStay(1L);
        order.verify(pets).flush();
        order.verify(events).publishEvent(new PetOwnerTransferredEvent(1L, 7L, 3L, 42L));
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        order.verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("PET_OWNER_TRANSFERRED");
        assertThat(entry.getValue().entityType()).isEqualTo("pets");
        assertThat(entry.getValue().entityId()).isEqualTo(1L);
        assertThat(pet.getCustomerId()).isEqualTo(3L);
    }

    @Test
    void transferLocksAscendingEvenWhenTheNewOwnerHasTheLargerId() {
        stubTransferTargets();

        service.transferOwner(1L, new TransferOwnerRequest(9L, true));

        InOrder order = inOrder(customers);
        order.verify(customers).findByIdForUpdate(7L);
        order.verify(customers).findByIdForUpdate(9L);
    }

    @Test
    void transferToTheCurrentOwnerIsRefusedBeforeAnyLock() {
        when(pets.findCustomerIdById(1L)).thenReturn(Optional.of(7L));

        assertThatThrownBy(() -> service.transferOwner(1L, new TransferOwnerRequest(7L, true)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KH-08");
        verifyNoInteractions(customers, events, audit);
        verify(pets, never()).findByIdForUpdate(any());
    }

    @Test
    void unknownNewOwnerIs404BeforeThePetIsLocked() {
        when(pets.findCustomerIdById(1L)).thenReturn(Optional.of(7L));
        when(customers.findByIdForUpdate(7L)).thenReturn(Optional.of(mock(Customer.class)));
        when(customers.findByIdForUpdate(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transferOwner(1L, new TransferOwnerRequest(3L, true)))
                .isInstanceOf(ResourceNotFoundException.class).hasMessageContaining("#3");
        verify(pets, never()).findByIdForUpdate(any());
        verifyNoInteractions(events, audit);
    }

    @Test
    void ownerChangedWhileWaitingIs409() {
        stubTransferTargets();
        pet.transferTo(8L);

        assertThatThrownBy(() -> service.transferOwner(1L, new TransferOwnerRequest(3L, true)))
                .isInstanceOf(ConcurrencyConflictException.class);
        assertThat(pet.getCustomerId()).isEqualTo(8L);
        verifyNoInteractions(events, audit);
    }

    @Test
    void deceasedPetCannotBeTransferred() {
        stubTransferTargets();
        pet.markDeceased(LocalDate.of(2026, 9, 1));

        assertThatThrownBy(() -> service.transferOwner(1L, new TransferOwnerRequest(3L, true)))
                .hasMessageContaining("BR-KH-05");
        verifyNoInteractions(visits, orders, boardings, events, audit);
    }

    @Test
    void everyBlockerIsNamedAndNothingIsWritten() {
        stubTransferTargets();
        when(visits.hasOpenVisit(1L)).thenReturn(true);
        when(orders.hasPendingOrderForPet(1L)).thenReturn(true);
        when(boardings.isPetInStay(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.transferOwner(1L, new TransferOwnerRequest(3L, true)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KH-08")
                .hasMessageContaining("lượt khám").hasMessageContaining("Order").hasMessageContaining("lưu trú");
        assertThat(pet.getCustomerId()).isEqualTo(7L);
        verify(pets, never()).flush();
        verifyNoInteractions(events, audit);
    }

    @Test
    void missingPetIs404() {
        when(pets.findCustomerIdById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transferOwner(5L, new TransferOwnerRequest(3L, true)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- giao dịch

    @Test
    void everyUseCaseIsOneTransactionThatSwallowsNothing() {
        for (String name : new String[] {"markDeceased", "deletePet", "transferOwner"}) {
            Transactional tx = null;
            for (Method method : PetLifecycleService.class.getMethods()) {
                if (method.getName().equals(name)) {
                    tx = method.getAnnotation(Transactional.class);
                }
            }
            assertThat(tx).as(name).isNotNull();
            assertThat(tx.readOnly()).as(name).isFalse();
            assertThat(tx.noRollbackFor()).as(name).isEmpty();
        }
    }
}
