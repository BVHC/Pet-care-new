package com.petcare.module.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.petcare.module.AiReadFixtures;
import com.petcare.module.AiReadItSupport;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.LivingPet;
import com.petcare.module.customer.api.Species;

/** I-1 (07 §11, AI-0): hồ sơ thú và thú còn sống của khách, đọc từ Postgres thật. */
class PetAiReadIT extends AiReadItSupport {

    @Autowired private PetQueryApi pets;

    @Test
    void profileCarriesBreedBirthDateAndTheNewestWeight() {
        long customer = AiReadFixtures.next();
        LocalDate birth = today().minusWeeks(20);
        long pet = fx.pet(customer, "Mochi", "DOG", birth, null, "poodle");
        fx.weight(pet, "3.20", "2026-10-01T03:00:00Z");
        fx.weight(pet, "4.50", "2026-11-01T03:00:00Z");

        PetQueryApi.PetProfile profile = pets.findPetProfile(pet).orElseThrow();

        assertThat(profile.petId()).isEqualTo(pet);
        assertThat(profile.customerId()).isEqualTo(customer);
        assertThat(profile.species()).isEqualTo(Species.DOG);
        assertThat(profile.breed()).isEqualTo("poodle");
        assertThat(profile.birthDate()).isEqualTo(birth);
        assertThat(profile.currentWeightKg()).isEqualByComparingTo(new BigDecimal("4.50"));
        assertThat(profile.deceased()).isFalse();
    }

    @Test
    void profileOfADeceasedPetIsReturnedWithDeceasedOnNotThrown() {
        LocalDate gone = today().minusDays(3);
        long pet = fx.pet(AiReadFixtures.next(), "Bông", "CAT", null, gone, null);

        PetQueryApi.PetProfile profile = pets.findPetProfile(pet).orElseThrow();

        assertThat(profile.deceasedOn()).isEqualTo(gone);
        assertThat(profile.deceased()).isTrue();
        assertThat(profile.currentWeightKg()).as("chưa cân").isNull();
        assertThat(profile.breed()).isNull();
    }

    @Test
    void profileOfAnUnknownPetIsEmpty() {
        assertThat(pets.findPetProfile(AiReadFixtures.next())).isEmpty();
    }

    @Test
    void livingPetsSkipDeceasedOnesAndOtherOwnersSortedByNameThenId() {
        long customer = AiReadFixtures.next();
        long other = AiReadFixtures.next();
        long muc = fx.pet(customer, "Muc", "DOG", null, null, null);
        long boFirst = fx.pet(customer, "Bo", "CAT", null, null, null);
        long boSecond = fx.pet(customer, "Bo", "DOG", null, null, null);
        fx.pet(customer, "Cun", "DOG", null, today().minusDays(1), null);
        fx.pet(other, "La", "DOG", null, null, null);

        assertThat(pets.findLivingPetsOfCustomer(customer)).containsExactly(
                new LivingPet(boFirst, "Bo"), new LivingPet(boSecond, "Bo"), new LivingPet(muc, "Muc"));
    }

    @Test
    void aPetTransferredToAnotherOwnerLeavesTheFormerOwnersList() {
        long former = AiReadFixtures.next();
        long newer = AiReadFixtures.next();
        long pet = fx.pet(former, "Nhan", "DOG", null, null, null);
        assertThat(pets.findLivingPetsOfCustomer(former)).extracting(LivingPet::petId).containsExactly(pet);

        fx.movePet(pet, newer);

        assertThat(pets.findLivingPetsOfCustomer(former)).isEmpty();
        assertThat(pets.findLivingPetsOfCustomer(newer)).extracting(LivingPet::petId).containsExactly(pet);
        assertThat(pets.findLivingPetsOfCustomer(AiReadFixtures.next())).isEmpty();
    }
}
