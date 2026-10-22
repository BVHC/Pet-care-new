package com.petcare.module.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.Species;
import com.petcare.module.customer.dto.CreatePetRequest;
import com.petcare.module.customer.dto.PetResponse;
import com.petcare.module.customer.dto.PetWriteResultResponse;
import com.petcare.module.customer.dto.UpdatePetRequest;
import com.petcare.module.customer.entity.Customer;
import com.petcare.module.customer.entity.Pet;
import com.petcare.module.customer.entity.PetSex;
import com.petcare.module.customer.mapper.CustomerStaffMapperImpl;
import com.petcare.module.customer.repository.CustomerRepository;
import com.petcare.module.customer.repository.PetRepository;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

/**
 * UC24 thêm / xem / sửa thú (docs/adr/0041): thứ tự khóa → chủ → đã mất → khóa loài → ngày sinh; mọi lần từ chối xảy ra
 * trước lệnh ghi đầu tiên; {@code null} giữ, rỗng xóa.
 */
class PetServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    private final PetRepository pets = mock(PetRepository.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final PetAccess access = mock(PetAccess.class);
    private final PetViews views = mock(PetViews.class);
    private final VisitQueryApi visits = mock(VisitQueryApi.class);
    private final PetService service = new PetService(pets, customers, access, views, visits,
            new CustomerStaffMapperImpl(), Clock.fixed(Instant.parse("2026-10-16T03:00:00Z"), ZoneOffset.UTC));

    private static Pet pet(long id, long customerId, String name, Species species) {
        Pet pet = Pet.create(customerId, name, species, PetSex.MALE, "Poodle", LocalDate.of(2024, 3, 15), false, "Nâu",
                false, "https://cdn.petcare.vn/p.jpg");
        ReflectionTestUtils.setField(pet, "id", id);
        return pet;
    }

    private static CreatePetRequest create(String name, Species species, LocalDate birthDate) {
        return new CreatePetRequest(name, species, PetSex.FEMALE, "  ", birthDate, null, null, null, null);
    }

    private void stubViewerStaff() {
        when(access.viewer()).thenReturn(new PetAccess.Viewer(null));
    }

    private void stubCreateEcho() {
        when(pets.saveAndFlush(any())).thenAnswer(call -> {
            Pet saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 90L);
            return saved;
        });
        when(views.view(any())).thenAnswer(call -> mock(PetResponse.class));
    }

    // ---------------------------------------------------------------- thêm

    @Test
    void receptionistCreateLocksTheCustomerFirstThenSavesAStrippedPet() {
        stubCreateEcho();
        when(customers.findByIdForUpdate(7L)).thenReturn(Optional.of(mock(Customer.class)));
        when(pets.existsDuplicateName(7L, "DOG", "Mực", 90L)).thenReturn(true);

        PetWriteResultResponse result = service.createForCustomer(7L, create("  Mực ", Species.DOG, TODAY));

        assertThat(result.duplicateNameWarning()).isTrue();
        InOrder order = inOrder(customers, pets);
        order.verify(customers).findByIdForUpdate(7L);
        order.verify(pets).saveAndFlush(org.mockito.ArgumentMatchers.argThat(p -> p.getName().equals("Mực")
                && p.getCustomerId().equals(7L) && p.getBreed() == null && !p.isBirthDateEstimated()
                && p.getBirthDate().equals(TODAY)));
    }

    @Test
    void createForUnknownCustomerIs404AndWritesNothing() {
        when(customers.findByIdForUpdate(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createForCustomer(7L, create("Mực", Species.DOG, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(pets, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsBirthDateInTheFutureButAcceptsToday() {
        stubCreateEcho();
        when(customers.findByIdForUpdate(7L)).thenReturn(Optional.of(mock(Customer.class)));

        assertThatThrownBy(() -> service.createForCustomer(7L, create("Mực", Species.DOG, TODAY.plusDays(1))))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KH-02");
        verify(pets, never()).saveAndFlush(any());

        service.createForCustomer(7L, create("Mực", Species.DOG, TODAY));
        verify(pets).saveAndFlush(any());
    }

    @Test
    void customerCreateUsesTheLockedOwnerFromPetAccess() {
        stubCreateEcho();
        Customer owner = mock(Customer.class);
        when(owner.getId()).thenReturn(5L);
        when(access.lockedOwner()).thenReturn(owner);

        service.createMyPet(create("Mực", Species.CAT, null));

        verify(pets).saveAndFlush(org.mockito.ArgumentMatchers.argThat(p -> p.getCustomerId().equals(5L)));
        verifyNoInteractions(customers);
    }

    @Test
    void customerCreateStopsWhenTheLinkDecisionIsPending() {
        doThrow(new BusinessRuleViolationException("BR-TK-19", "chờ liên kết")).when(access).lockedOwner();

        assertThatThrownBy(() -> service.createMyPet(create("Mực", Species.CAT, null)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-TK-19");
        verify(pets, never()).saveAndFlush(any());
    }

    // ---------------------------------------------------------------- sửa

    @Test
    void updateLocksThePetBeforeAnythingElseAndKeepsUnsentFields() {
        stubViewerStaff();
        Pet pet = pet(1L, 7L, "Mực", Species.DOG);
        when(pets.findByIdForUpdate(1L)).thenReturn(Optional.of(pet));
        when(views.view(pet)).thenReturn(mock(PetResponse.class));

        PetWriteResultResponse result = service.updatePet(1L, new UpdatePetRequest(null, null, null, null, null,
                null, null, null, null));

        assertThat(result.duplicateNameWarning()).isFalse();
        assertThat(pet.getName()).isEqualTo("Mực");
        assertThat(pet.getBreed()).isEqualTo("Poodle");
        assertThat(pet.getNeutered()).isFalse();
        InOrder order = inOrder(access, pets);
        order.verify(access).viewer();
        order.verify(pets).findByIdForUpdate(1L);
        order.verify(access).requireVisible(any(), eq(pet));
        order.verify(pets).flush();
        verify(pets, never()).findById(anyLong());
        verifyNoInteractions(visits);
    }

    @Test
    void updateEmptyStringClearsOptionalFieldsAndSentValuesReplace() {
        stubViewerStaff();
        Pet pet = pet(1L, 7L, "Mực", Species.DOG);
        when(pets.findByIdForUpdate(1L)).thenReturn(Optional.of(pet));
        when(views.view(pet)).thenReturn(mock(PetResponse.class));

        service.updatePet(1L, new UpdatePetRequest(" Mực con ", null, PetSex.FEMALE, "", LocalDate.of(2023, 1, 2),
                true, "   ", true, ""));

        assertThat(pet.getName()).isEqualTo("Mực con");
        assertThat(pet.getSex()).isEqualTo(PetSex.FEMALE);
        assertThat(pet.getBreed()).isNull();
        assertThat(pet.getColor()).isNull();
        assertThat(pet.getPhotoUrl()).isNull();
        assertThat(pet.getBirthDate()).isEqualTo(LocalDate.of(2023, 1, 2));
        assertThat(pet.isBirthDateEstimated()).isTrue();
        assertThat(pet.getNeutered()).isTrue();
    }

    @Test
    void changingSpeciesIsRefusedOnceThereIsMedicalHistory() {
        stubViewerStaff();
        Pet pet = pet(1L, 7L, "Mực", Species.DOG);
        when(pets.findByIdForUpdate(1L)).thenReturn(Optional.of(pet));
        when(visits.hasMedicalHistory(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.updatePet(1L, new UpdatePetRequest(null, Species.CAT, null, null, null,
                null, null, null, null))).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("BR-KH-03");
        assertThat(pet.getSpecies()).isEqualTo(Species.DOG);
        verify(pets, never()).flush();
    }

    @Test
    void sendingTheSameSpeciesDoesNotAskVisitAndAChangeWithoutHistoryIsAllowed() {
        stubViewerStaff();
        Pet pet = pet(1L, 7L, "Mực", Species.DOG);
        when(pets.findByIdForUpdate(1L)).thenReturn(Optional.of(pet));
        when(views.view(pet)).thenReturn(mock(PetResponse.class));

        service.updatePet(1L, new UpdatePetRequest(null, Species.DOG, null, null, null, null, null, null, null));
        verifyNoInteractions(visits);

        when(visits.hasMedicalHistory(1L)).thenReturn(false);
        service.updatePet(1L, new UpdatePetRequest(null, Species.CAT, null, null, null, null, null, null, null));
        assertThat(pet.getSpecies()).isEqualTo(Species.CAT);
    }

    @Test
    void deceasedPetIsReadOnlyBeforeAnyOtherCheck() {
        stubViewerStaff();
        Pet pet = pet(1L, 7L, "Mực", Species.DOG);
        pet.markDeceased(LocalDate.of(2026, 9, 1));
        when(pets.findByIdForUpdate(1L)).thenReturn(Optional.of(pet));

        assertThatThrownBy(() -> service.updatePet(1L, new UpdatePetRequest(null, Species.CAT, null, null,
                TODAY.plusDays(3), null, null, null, null))).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("BR-KH-05");
        verifyNoInteractions(visits);
        verify(pets, never()).flush();
    }

    @Test
    void updateRejectsAFutureBirthDate() {
        stubViewerStaff();
        Pet pet = pet(1L, 7L, "Mực", Species.DOG);
        when(pets.findByIdForUpdate(1L)).thenReturn(Optional.of(pet));

        assertThatThrownBy(() -> service.updatePet(1L, new UpdatePetRequest(null, null, null, null,
                TODAY.plusDays(1), null, null, null, null))).hasMessageContaining("BR-KH-02");
        assertThat(pet.getBirthDate()).isEqualTo(LocalDate.of(2024, 3, 15));
    }

    @Test
    void duplicateWarningOnlyWhenNameOrSpeciesChanged() {
        stubViewerStaff();
        Pet pet = pet(1L, 7L, "Mực", Species.DOG);
        when(pets.findByIdForUpdate(1L)).thenReturn(Optional.of(pet));
        when(views.view(pet)).thenReturn(mock(PetResponse.class));
        when(pets.existsDuplicateName(7L, "DOG", "Bông", 1L)).thenReturn(true);

        assertThat(service.updatePet(1L, new UpdatePetRequest(null, null, null, "Poodle", null, null, null, null,
                null)).duplicateNameWarning()).isFalse();
        verify(pets, never()).existsDuplicateName(anyLong(), anyString(), anyString(), anyLong());

        assertThat(service.updatePet(1L, new UpdatePetRequest("Bông", null, null, null, null, null, null, null,
                null)).duplicateNameWarning()).isTrue();
    }

    @Test
    void updateOfAMissingOrForeignPetIs404() {
        stubViewerStaff();
        when(pets.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePet(1L, new UpdatePetRequest(null, null, null, null, null, null, null,
                null, null))).isInstanceOf(ResourceNotFoundException.class);

        Pet pet = pet(2L, 7L, "Mực", Species.DOG);
        when(pets.findByIdForUpdate(2L)).thenReturn(Optional.of(pet));
        doThrow(new ResourceNotFoundException("thú cưng", 2L)).when(access).requireVisible(any(), eq(pet));
        assertThatThrownBy(() -> service.updatePet(2L, new UpdatePetRequest("Tên khác", null, null, null, null,
                null, null, null, null))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(pet.getName()).isEqualTo("Mực");
    }

    // ---------------------------------------------------------------- đọc, giao dịch

    @Test
    void getPetChecksVisibilityAndMissingIs404() {
        stubViewerStaff();
        Pet pet = pet(1L, 7L, "Mực", Species.DOG);
        when(pets.findById(1L)).thenReturn(Optional.of(pet));
        when(views.view(pet)).thenReturn(mock(PetResponse.class));

        service.getPet(1L);

        verify(access).requireVisible(any(), eq(pet));
        assertThatThrownBy(() -> service.getPet(2L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void readsAreReadOnlyAndWritesDoNotSwallowErrors() throws Exception {
        for (String name : new String[] {"listMyPets", "getCustomerDetail"}) {
            Method method = findMethod(name);
            assertThat(method.getAnnotation(Transactional.class).readOnly()).as(name).isTrue();
        }
        for (String name : new String[] {"createMyPet", "createForCustomer", "updatePet"}) {
            Transactional tx = findMethod(name).getAnnotation(Transactional.class);
            assertThat(tx.readOnly()).as(name).isFalse();
            assertThat(tx.noRollbackFor()).as(name).isEmpty();
        }
    }

    private static Method findMethod(String name) {
        for (Method method : PetService.class.getMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new AssertionError(name);
    }
}
