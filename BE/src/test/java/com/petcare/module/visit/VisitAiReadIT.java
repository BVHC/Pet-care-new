package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.petcare.module.AiReadFixtures;
import com.petcare.module.AiReadItSupport;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.visit.api.VaccinationQueryApi;
import com.petcare.module.visit.api.VaccinationQueryApi.DoseRecord;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.module.visit.api.VisitQueryApi.AgeGroup;

/**
 * I-2 và I-6 (07 §11, AI-0) trên Postgres thật. Dịch vụ của một lượt là các dòng SERVICE của Order nguồn VISIT (bảng
 * {@code visits} không có {@code service_id}); đếm "toàn chuỗi" nên chỉ khẳng định trên dịch vụ do chính test tạo.
 */
class VisitAiReadIT extends AiReadItSupport {

    @Autowired private VaccinationQueryApi vaccinations;
    @Autowired private VisitQueryApi visits;

    // ------------------------------------------------------------------ I-2

    @Test
    void doseHistoryIsNewestFirstAndKeepsSupersededDoses() {
        long customer = AiReadFixtures.next();
        long pet = fx.pet(customer, "DOG", null);
        long otherPet = fx.pet(customer, "DOG", null);
        long type = AiReadFixtures.next();
        LocalDate d = today();
        long first = fx.vaccination(pet, type, 1, d.minusMonths(6), d.minusMonths(3), true);
        long second = fx.vaccination(pet, type, 2, d.minusMonths(1), d.plusMonths(11), false);
        long third = fx.vaccination(pet, type + 1, 1, d.minusMonths(1), d.plusMonths(11), false);
        fx.vaccination(otherPet, type, 1, d.minusDays(1), d.plusYears(1), false);

        List<DoseRecord> history = vaccinations.findDoseHistory(pet);

        assertThat(history).extracting(DoseRecord::vaccinationId).as("mới nhất trước, cùng ngày thì mũi ghi sau")
                .containsExactly(third, second, first);
        assertThat(history).extracting(DoseRecord::superseded).containsExactly(false, false, true);
        assertThat(history.get(2)).isEqualTo(new DoseRecord(first, type, 1, d.minusMonths(6), d.minusMonths(3), true));
    }

    @Test
    void doseHistoryOfAPetWithoutDosesIsEmpty() {
        assertThat(vaccinations.findDoseHistory(fx.pet(AiReadFixtures.next(), "CAT", null))).isEmpty();
    }

    // ------------------------------------------------------------------ I-6 findLastServiceDates

    @Test
    void lastServiceDatesTakeTheNewestCompletedVisitPerServiceAndIgnoreTheRest() {
        long customer = AiReadFixtures.next();
        long pet = fx.pet(customer, "DOG", null);
        long otherPet = fx.pet(customer, "DOG", null);
        long svcA = AiReadFixtures.next();
        long svcB = AiReadFixtures.next();
        long svcWaiting = AiReadFixtures.next();
        long svcCancelled = AiReadFixtures.next();
        long svcOtherPet = AiReadFixtures.next();
        LocalDate t = today();
        completedVisit(customer, pet, noonVn(t.minusDays(10)), svcA);
        completedVisit(customer, pet, noonVn(t.minusDays(3)), svcA, svcB);
        openVisit(customer, pet, "WAITING", svcWaiting);
        openVisit(customer, pet, "CANCELLED", svcCancelled);
        completedVisit(customer, otherPet, noonVn(t.minusDays(3)), svcOtherPet);
        fx.visit(customer, pet, "COMPLETED", noonVn(t.minusDays(2)));

        assertThat(visits.findLastServiceDates(pet))
                .as("lượt chưa hoàn tất, lượt của thú khác, lượt không có Order đều không tính")
                .containsOnly(entry(svcA, t.minusDays(3)), entry(svcB, t.minusDays(3)));
    }

    @Test
    void lastServiceDateIsTheVietnamDayNotTheUtcDay() {
        long customer = AiReadFixtures.next();
        long pet = fx.pet(customer, "DOG", null);
        long svc = AiReadFixtures.next();
        Instant lateUtcEvening = LocalDate.of(2026, 11, 1).atTime(17, 30).toInstant(ZoneOffset.UTC);
        completedVisit(customer, pet, lateUtcEvening, svc);

        assertThat(visits.findLastServiceDates(pet)).containsOnly(entry(svc, LocalDate.of(2026, 11, 2)));
    }

    // ------------------------------------------------------------------ I-6 countCompletedByService

    @Test
    void countsPerServiceAreSplitByAgeGroupAndSpeciesAtTheExactBoundaries() {
        long customer = AiReadFixtures.next();
        long svc = AiReadFixtures.next();
        LocalDate t = today();
        long senior = seniorYearsOfDog() * 364L;
        long[] dogs = {
                fx.pet(customer, "DOG", t.minusDays(363)),
                fx.pet(customer, "DOG", t.minusDays(364)),
                fx.pet(customer, "DOG", t.minusDays(senior - 1)),
                fx.pet(customer, "DOG", t.minusDays(senior)),
                fx.pet(customer, "DOG", null)};
        long catJunior = fx.pet(customer, "CAT", t.minusDays(363));
        for (long pet : dogs) {
            completedVisit(customer, pet, noonVn(t.minusDays(1)), svc);
        }
        completedVisit(customer, catJunior, noonVn(t.minusDays(1)), svc);
        LocalDate since = t.minusDays(30);

        assertThat(visits.countCompletedByService(Species.DOG, AgeGroup.JUNIOR, since)).containsEntry(svc, 1L);
        assertThat(visits.countCompletedByService(Species.DOG, AgeGroup.ADULT, since))
                .as("đúng 364 ngày và ngay dưới ngưỡng SENIOR đều là ADULT").containsEntry(svc, 2L);
        assertThat(visits.countCompletedByService(Species.DOG, AgeGroup.SENIOR, since)).containsEntry(svc, 1L);
        assertThat(visits.countCompletedByService(Species.DOG, AgeGroup.UNKNOWN, since)).containsEntry(svc, 1L);
        assertThat(visits.countCompletedByService(Species.CAT, AgeGroup.JUNIOR, since)).containsEntry(svc, 1L);
        assertThat(visits.countCompletedByService(Species.CAT, AgeGroup.ADULT, since)).doesNotContainKey(svc);
    }

    /** 07 §14.3: nhóm tuổi theo loài (mặc định chó 7 năm, mèo 10 năm): mỗi loài đọc đúng khóa [CFG] của nó. */
    @Test
    void seniorThresholdFollowsTheSpeciesOfThePet() {
        long customer = AiReadFixtures.next();
        long svc = AiReadFixtures.next();
        LocalDate t = today();
        long catDays = seniorDays(ConfigKey.AI_RECO_SENIOR_AGE_YEARS_CAT);
        long otherDays = seniorDays(ConfigKey.AI_RECO_SENIOR_AGE_YEARS_OTHER);
        long[] pets = {
                fx.pet(customer, "CAT", t.minusDays(catDays - 1)),
                fx.pet(customer, "CAT", t.minusDays(catDays)),
                fx.pet(customer, "OTHER", t.minusDays(otherDays - 1)),
                fx.pet(customer, "OTHER", t.minusDays(otherDays))};
        for (long pet : pets) {
            completedVisit(customer, pet, noonVn(t.minusDays(1)), svc);
        }
        LocalDate since = t.minusDays(30);

        assertThat(visits.countCompletedByService(Species.CAT, AgeGroup.ADULT, since)).containsEntry(svc, 1L);
        assertThat(visits.countCompletedByService(Species.CAT, AgeGroup.SENIOR, since)).containsEntry(svc, 1L);
        assertThat(visits.countCompletedByService(Species.OTHER, AgeGroup.ADULT, since)).containsEntry(svc, 1L);
        assertThat(visits.countCompletedByService(Species.OTHER, AgeGroup.SENIOR, since)).containsEntry(svc, 1L);
    }

    @Test
    void sinceIncludesTheWholeFirstDayInVietnamTimeButNotTheMomentBefore() {
        long customer = AiReadFixtures.next();
        long svc = AiReadFixtures.next();
        long pet = fx.pet(customer, "DOG", today().minusDays(100));
        LocalDate since = today().minusDays(10);
        completedVisit(customer, pet, startOfDayVn(since), svc);
        completedVisit(customer, pet, startOfDayVn(since).minusMillis(1), svc);
        completedVisit(customer, pet, startOfDayVn(since.plusDays(1)).minusMillis(1), svc);

        assertThat(visits.countCompletedByService(Species.DOG, AgeGroup.JUNIOR, since)).containsEntry(svc, 2L);
    }

    @Test
    void onlyCompletedVisitsCountAndAVisitWithTwoLinesOfOneServiceCountsOnce() {
        long customer = AiReadFixtures.next();
        long svc = AiReadFixtures.next();
        long pet = fx.pet(customer, "DOG", today().minusDays(100));
        Instant yesterday = noonVn(today().minusDays(1));
        completedVisit(customer, pet, yesterday, svc, svc);
        completedVisit(customer, pet, yesterday, svc);
        openVisit(customer, pet, "WAITING", svc);
        openVisit(customer, pet, "CANCELLED", svc);
        openVisit(customer, pet, "IN_PROGRESS", svc);

        assertThat(visits.countCompletedByService(Species.DOG, AgeGroup.JUNIOR, today().minusDays(30)))
                .containsEntry(svc, 2L);
    }

    // ------------------------------------------------------------------ dữ liệu

    private long completedVisit(long customer, long pet, Instant completedAt, long... serviceIds) {
        long visit = fx.visit(customer, pet, "COMPLETED", completedAt);
        long order = fx.order(customer, visit, "PAID", completedAt);
        for (long serviceId : serviceIds) {
            fx.serviceLine(order, serviceId);
        }
        return visit;
    }

    /** Lượt chưa hoàn tất nhưng có {@code completed_at}: chỉ bộ lọc trạng thái COMPLETED mới loại được nó. */
    private void openVisit(long customer, long pet, String status, long serviceId) {
        long visit = fx.visit(customer, pet, status, noonVn(today().minusDays(1)));
        fx.serviceLine(fx.order(customer, visit, "CANCELLED".equals(status) ? "CANCELLED" : "OPEN", null), serviceId);
    }
}
