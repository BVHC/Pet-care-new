package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.visit.api.VaccinationQueryApi;
import com.petcare.module.visit.api.VaccinationQueryApi.BoardingVaccineGap;

/**
 * {@code VaccinationQueryApi} thật trên Postgres (T28 phần 0, docs/adr/0072): khoảng trống mũi tiêm bắt buộc khi lưu trú
 * (BR-LT-05) và kiểm loại vaccine đã dùng (BR-SP-07) qua {@code DELETE /api/vaccine-types/{id}}. Mũi tiêm chèn bằng SQL
 * ({@link VaccinationItSupport}) vì chưa có endpoint ghi. Kho dùng chung DB nên mọi khẳng định chỉ xét các loại vaccine do
 * test tự tạo. Ngày dùng cố định năm 2012, không phụ thuộc đồng hồ.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class VaccinationQueryApiIT {

    private static final LocalDate ON = LocalDate.of(2012, 6, 15);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private VaccinationQueryApi api;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TestRestTemplate http;
    @Autowired
    private SessionService sessions;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private AppointmentItSupport data;
    private VaccinationItSupport vaccines;
    private long branch;
    private long vet;
    private long pet;
    private long catPet;
    private long visit;
    private long dogRequiredA;
    private long dogRequiredB;
    private long dogOptional;
    private long catRequired;
    private long protocolA;
    private long productA;
    private long lotA;

    @BeforeEach
    void setUp() {
        data = new AppointmentItSupport(jdbc);
        vaccines = new VaccinationItSupport(jdbc);
        branch = data.branch("ACTIVE");
        vet = data.staff("VET", branch);
        long customer = data.counterCustomer();
        pet = data.pet(customer);
        catPet = jdbc.queryForObject("""
                INSERT INTO pets (customer_id, name, species, sex) VALUES (?, 'Mimi', 'CAT', 'FEMALE') RETURNING id""",
                Long.class, customer);
        long service = data.service("MEDICAL");
        visit = data.visit(data.appointment(customer, pet, branch, service, "MEDICAL", ON, java.time.LocalTime.of(9, 0),
                "BOOKED", vet), vet);

        dogRequiredA = vaccines.vaccineType("DOG", "Dại IT");
        dogRequiredB = vaccines.vaccineType("DOG", "5 bệnh IT");
        dogOptional = vaccines.vaccineType("DOG", "Ho cũi IT");
        catRequired = vaccines.vaccineType("CAT", "4 bệnh IT");
        protocolA = vaccines.protocol("DOG", dogRequiredA, 1, 365, true);
        vaccines.protocol("DOG", dogRequiredB, 1, 365, true);
        vaccines.protocol("DOG", dogOptional, 1, 365, false);
        vaccines.protocol("CAT", catRequired, 1, 365, true);
        productA = vaccines.product(dogRequiredA, "Rabisin", true);
        lotA = vaccines.lot(branch, productA);
    }

    @AfterEach
    void tearDown() {
        vaccines.deleteDoses();
        jdbc.update("DELETE FROM pets WHERE id = ?", catPet);
        vaccines.deleteCatalog();
        data.cleanUp();
    }

    private long dose(long typeId, LocalDate given, LocalDate nextDue) {
        return vaccines.dose(visit, typeId, protocolA, 1, productA, lotA, given, nextDue, vet, null);
    }

    /** Khoảng trống của thú theo đúng các loại do test tạo (bỏ loại của test khác dùng chung DB). */
    private List<BoardingVaccineGap> gaps(long petId, LocalDate onDate, long... typeIds) {
        Set<Long> mine = java.util.stream.LongStream.of(typeIds).boxed().collect(java.util.stream.Collectors.toSet());
        return api.findBoardingVaccineGaps(petId, onDate).stream().filter(g -> mine.contains(g.vaccineTypeId()))
                .toList();
    }

    // ================================================================ findBoardingVaccineGaps (BR-LT-05)

    @Test
    void neverGivenIsAGapWithNullDatesAndGapsAreSortedByTypeId() {
        assertThat(gaps(pet, ON, dogRequiredA, dogRequiredB)).containsExactly(
                new BoardingVaccineGap(dogRequiredA, null, null), new BoardingVaccineGap(dogRequiredB, null, null));
    }

    @Test
    void aDoseDueBeforeTheDateIsAGapAndDueOnTheDateOrLaterIsNot() {
        dose(dogRequiredA, ON.minusDays(366), ON.minusDays(1));
        dose(dogRequiredB, ON.minusDays(365), ON);

        assertThat(gaps(pet, ON, dogRequiredA, dogRequiredB))
                .containsExactly(new BoardingVaccineGap(dogRequiredA, ON.minusDays(366), ON.minusDays(1)));
        assertThat(gaps(pet, ON.minusDays(1), dogRequiredA, dogRequiredB)).as("đúng ngày tái chủng vẫn đạt").isEmpty();
    }

    @Test
    void theLatestDoseDecidesNotTheOldOnes() {
        dose(dogRequiredA, ON.minusDays(800), ON.minusDays(435));
        dose(dogRequiredA, ON.minusDays(30), ON.plusDays(335));

        assertThat(gaps(pet, ON, dogRequiredA)).isEmpty();
    }

    @Test
    void twoDosesOnTheSameDayTheLaterRecordedOneDecides() {
        LocalDate day = ON.minusDays(40);
        dose(dogRequiredA, day, ON.minusDays(5));
        dose(dogRequiredA, day, ON.plusDays(325));

        assertThat(gaps(pet, ON, dogRequiredA)).isEmpty();
    }

    @Test
    void aSupersededDoseStillCountsAsTheLatestWhenNothingNewerExists() {
        vaccines.dose(visit, dogRequiredA, protocolA, 1, productA, lotA, ON.minusDays(10), ON.plusDays(355), vet,
                java.time.Instant.parse("2012-06-01T00:00:00Z"));

        assertThat(gaps(pet, ON, dogRequiredA)).isEmpty();
    }

    @Test
    void aTypeNotRequiredForBoardingIsIgnored() {
        assertThat(gaps(pet, ON, dogOptional)).isEmpty();
    }

    @Test
    void anotherPetsDosesDoNotCount() {
        long otherPet = data.pet(data.counterCustomer());
        long otherService = data.service("MEDICAL");
        long customer = jdbc.queryForObject("SELECT customer_id FROM pets WHERE id = ?", Long.class, otherPet);
        long otherVisit = data.visit(data.appointment(customer, otherPet, branch, otherService, "MEDICAL", ON,
                java.time.LocalTime.of(10, 0), "BOOKED", vet), vet);
        vaccines.dose(otherVisit, dogRequiredA, protocolA, 1, productA, lotA, ON.minusDays(5), ON.plusDays(360), vet,
                null);

        assertThat(gaps(pet, ON, dogRequiredA)).containsExactly(new BoardingVaccineGap(dogRequiredA, null, null));
        assertThat(gaps(otherPet, ON, dogRequiredA)).isEmpty();
    }

    @Test
    void theSpeciesOfThePetSelectsTheRequiredTypes() {
        assertThat(gaps(catPet, ON, catRequired, dogRequiredA, dogRequiredB))
                .containsExactly(new BoardingVaccineGap(catRequired, null, null));
        assertThat(gaps(pet, ON, catRequired, dogRequiredA, dogRequiredB)).extracting(BoardingVaccineGap::vaccineTypeId)
                .containsExactly(dogRequiredA, dogRequiredB);
    }

    @Test
    void anInactiveProtocolDoesNotMakeATypeRequired() {
        jdbc.update("UPDATE vaccination_protocols SET is_active = false WHERE vaccine_type_id = ?", dogRequiredB);

        assertThat(gaps(pet, ON, dogRequiredA, dogRequiredB)).extracting(BoardingVaccineGap::vaccineTypeId)
                .containsExactly(dogRequiredA);
    }

    // ================================================================ existsByVaccineType + xóa loại (BR-SP-07)

    @Test
    void existsByVaccineTypeFollowsTheDoses() {
        assertThat(api.existsByVaccineType(dogRequiredA)).isFalse();

        dose(dogRequiredA, ON.minusDays(1), ON.plusDays(364));

        assertThat(api.existsByVaccineType(dogRequiredA)).isTrue();
        assertThat(api.existsByVaccineType(dogRequiredB)).isFalse();
    }

    @Test
    void deletingATypeThatHasADoseIs400Br_Sp_07AndKeepsTheRow() {
        // loại không có phác đồ / sản phẩm: chỉ mũi tiêm giữ nó (mũi trỏ phác đồ và sản phẩm của loại khác)
        long lonely = vaccines.vaccineType("DOG", "Chỉ có mũi IT");
        dose(lonely, ON.minusDays(1), ON.plusDays(364));

        ResponseEntity<Map<String, Object>> response = delete(superManagerToken(), lonely);

        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("errorCode", "BUSINESS_RULE_VIOLATION")
                .containsEntry("statusCode", 400);
        assertThat((String) response.getBody().get("message")).endsWith("(BR-SP-07)");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vaccine_types WHERE id = ?", Long.class, lonely))
                .isEqualTo(1L);
    }

    @Test
    void deletingAnUnusedTypeIs204AndNoLongerA500() {
        long unused = vaccines.vaccineType("DOG", "Chưa dùng IT");

        ResponseEntity<Map<String, Object>> response = delete(superManagerToken(), unused);

        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vaccine_types WHERE id = ?", Long.class, unused))
                .isZero();
    }

    private ResponseEntity<Map<String, Object>> delete(String bearer, long typeId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        return http.exchange(URI.create(http.getRootUri() + "/api/vaccine-types/" + typeId), HttpMethod.DELETE,
                new HttpEntity<>(null, headers), MAP);
    }

    private String superManagerToken() {
        long account = data.staff("SUPER_MANAGER", null);
        return new TransactionTemplate(transactionManager)
                .execute(s -> sessions.open(account, "203.0.113.9", "VaccinationQueryApiIT" + UUID.randomUUID()))
                .accessToken();
    }
}
