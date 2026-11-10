package com.petcare.module.boarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.petcare.module.AiReadFixtures;
import com.petcare.module.AiReadItSupport;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.boarding.api.BoardingQueryApi.StayOfPet;
import com.petcare.module.boarding.api.BoardingQueryApi.StayStatus;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.visit.api.VisitQueryApi.AgeGroup;

/**
 * I-7 (07 §11, AI-0) trên Postgres thật. Loài và tuổi của thú lấy qua {@code PetQueryApi}; ranh giới nhóm tuổi phải trùng
 * với visit (cùng {@code AgeGroup.of}: 364 ngày, {@code ngưỡng × 364} ngày).
 */
class BoardingAiReadIT extends AiReadItSupport {

    @Autowired private BoardingQueryApi boarding;

    @Test
    void staysOfAPetComeNewestCheckInFirstWithEveryStatus() {
        long customer = AiReadFixtures.next();
        long pet = fx.pet(customer, "DOG", null);
        long otherPet = fx.pet(customer, "DOG", null);
        long kennel1 = AiReadFixtures.next();
        long kennel2 = AiReadFixtures.next();
        LocalDate t = today();
        long old = fx.booking(customer, pet, kennel1, "CHECKED_OUT", t.minusDays(30), noonVn(t.minusDays(28)));
        long cancelled = fx.booking(customer, pet, kennel2, "CANCELLED", t.minusDays(10), null);
        long upcoming = fx.booking(customer, pet, kennel1, "BOOKED", t.plusDays(10), null);
        fx.booking(customer, otherPet, kennel1, "BOOKED", t.plusDays(10), null);

        List<StayOfPet> stays = boarding.findStaysOfPet(pet);

        assertThat(stays).extracting(StayOfPet::bookingId).containsExactly(upcoming, cancelled, old);
        assertThat(stays.get(0)).isEqualTo(new StayOfPet(upcoming, kennel1, StayStatus.BOOKED, t.plusDays(10),
                t.plusDays(12)));
        assertThat(stays).extracting(StayOfPet::status)
                .containsExactly(StayStatus.BOOKED, StayStatus.CANCELLED, StayStatus.CHECKED_OUT);
        assertThat(boarding.findStaysOfPet(fx.pet(customer, "CAT", null))).isEmpty();
    }

    @Test
    void checkedOutStaysAreCountedPerKennelTypeForTheAgeGroupAndSpeciesAtTheExactBoundaries() {
        long customer = AiReadFixtures.next();
        long kennel = AiReadFixtures.next();
        LocalDate t = today();
        long senior = seniorYearsOfDog() * 364L;
        Instant out = noonVn(t.minusDays(1));
        long[] dogs = {
                fx.pet(customer, "DOG", t.minusDays(363)),
                fx.pet(customer, "DOG", t.minusDays(364)),
                fx.pet(customer, "DOG", t.minusDays(senior - 1)),
                fx.pet(customer, "DOG", t.minusDays(senior)),
                fx.pet(customer, "DOG", null)};
        long catJunior = fx.pet(customer, "CAT", t.minusDays(363));
        for (long pet : dogs) {
            fx.booking(customer, pet, kennel, "CHECKED_OUT", t.minusDays(5), out);
        }
        fx.booking(customer, catJunior, kennel, "CHECKED_OUT", t.minusDays(5), out);
        LocalDate since = t.minusDays(30);

        assertThat(boarding.countStaysByKennelType(Species.DOG, AgeGroup.JUNIOR, since)).containsEntry(kennel, 1L);
        assertThat(boarding.countStaysByKennelType(Species.DOG, AgeGroup.ADULT, since))
                .as("đúng 364 ngày và ngay dưới ngưỡng SENIOR đều là ADULT").containsEntry(kennel, 2L);
        assertThat(boarding.countStaysByKennelType(Species.DOG, AgeGroup.SENIOR, since)).containsEntry(kennel, 1L);
        assertThat(boarding.countStaysByKennelType(Species.DOG, AgeGroup.UNKNOWN, since)).containsEntry(kennel, 1L);
        assertThat(boarding.countStaysByKennelType(Species.CAT, AgeGroup.JUNIOR, since)).containsEntry(kennel, 1L);
        assertThat(boarding.countStaysByKennelType(Species.CAT, AgeGroup.ADULT, since)).doesNotContainKey(kennel);
    }

    /** 07 §14.3: nhóm tuổi theo loài (mặc định chó 7 năm, mèo 10 năm): mỗi loài đọc đúng khóa [CFG] của nó. */
    @Test
    void seniorThresholdFollowsTheSpeciesOfThePet() {
        long customer = AiReadFixtures.next();
        long kennel = AiReadFixtures.next();
        LocalDate t = today();
        long catDays = seniorDays(ConfigKey.AI_RECO_SENIOR_AGE_YEARS_CAT);
        long otherDays = seniorDays(ConfigKey.AI_RECO_SENIOR_AGE_YEARS_OTHER);
        Instant out = noonVn(t.minusDays(1));
        long[] pets = {
                fx.pet(customer, "CAT", t.minusDays(catDays - 1)),
                fx.pet(customer, "CAT", t.minusDays(catDays)),
                fx.pet(customer, "OTHER", t.minusDays(otherDays - 1)),
                fx.pet(customer, "OTHER", t.minusDays(otherDays))};
        for (long pet : pets) {
            fx.booking(customer, pet, kennel, "CHECKED_OUT", t.minusDays(5), out);
        }
        LocalDate since = t.minusDays(30);

        assertThat(boarding.countStaysByKennelType(Species.CAT, AgeGroup.ADULT, since)).containsEntry(kennel, 1L);
        assertThat(boarding.countStaysByKennelType(Species.CAT, AgeGroup.SENIOR, since)).containsEntry(kennel, 1L);
        assertThat(boarding.countStaysByKennelType(Species.OTHER, AgeGroup.ADULT, since)).containsEntry(kennel, 1L);
        assertThat(boarding.countStaysByKennelType(Species.OTHER, AgeGroup.SENIOR, since)).containsEntry(kennel, 1L);
    }

    @Test
    void onlyCheckedOutStaysCountAndSinceIncludesTheWholeFirstDayInVietnamTime() {
        long customer = AiReadFixtures.next();
        long kennel = AiReadFixtures.next();
        LocalDate t = today();
        long pet = fx.pet(customer, "DOG", t.minusDays(100));
        LocalDate since = t.minusDays(10);
        fx.booking(customer, pet, kennel, "CHECKED_OUT", t.minusDays(40), startOfDayVn(since));
        fx.booking(customer, pet, kennel, "CHECKED_OUT", t.minusDays(45), startOfDayVn(since).minusMillis(1));
        fx.booking(customer, pet, kennel, "CHECKED_OUT", t.minusDays(50), startOfDayVn(since.plusDays(1)).minusMillis(1));
        // có actual_check_out_at ở mọi trạng thái: chỉ bộ lọc CHECKED_OUT mới loại được ba dòng dưới
        Instant inRange = startOfDayVn(since.plusDays(1));
        fx.booking(customer, pet, kennel, "CHECKED_IN", t.minusDays(1), inRange);
        fx.booking(customer, pet, kennel, "BOOKED", t.plusDays(20), inRange);
        fx.booking(customer, pet, kennel, "CANCELLED", t.plusDays(40), inRange);

        assertThat(boarding.countStaysByKennelType(Species.DOG, AgeGroup.JUNIOR, since)).containsEntry(kennel, 2L);
    }
}
