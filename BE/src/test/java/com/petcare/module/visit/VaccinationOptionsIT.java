package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import com.petcare.support.MutableClock;

/**
 * visit-v1 #18 gợi ý mũi tiêm trên Postgres thật (T28 phần 1, docs/adr/0073): phác đồ đang dùng, mũi gần nhất ở mọi chi
 * nhánh, gợi ý A18, chưa đủ tuổi theo đồng hồ, sản phẩm đang kinh doanh còn tồn khả dụng của chi nhánh của lượt; mọi guard
 * G0–G9 và thứ tự lỗi; không ghi gì. Lượt và mũi tiêm chèn bằng SQL. Đồng hồ {@link MutableClock} ở <b>năm 2013</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, VaccinationOptionsIT.TestBeans.class})
class VaccinationOptionsIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2013, 5, 10);
    private static final Instant T0 = TODAY.atTime(9, 0).atZone(VN).toInstant();
    private static final String PATH = "/api/visits/%d/vaccination-options";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SessionService sessions;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MutableClock clock;

    private AppointmentItSupport data;
    private VaccinationItSupport vaccines;
    private TransactionTemplate tx;
    private final List<Long> branches = new ArrayList<>();
    private long branch;
    private long otherBranch;
    private long vet;
    private long otherVet;
    private long foreignVet;
    private long customer;
    private long pet;
    private long visit;
    private long dogType;
    private long dose1;
    private long dose2Inactive;
    private long dose3;
    private String vetToken;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        vaccines = new VaccinationItSupport(jdbc);
        branch = branch();
        otherBranch = branch();
        vet = data.staff("VET", branch);
        otherVet = data.staff("VET", branch);
        foreignVet = data.staff("VET", otherBranch);
        customer = data.counterCustomer();
        pet = data.pet(customer);
        visit = visit(branch, "MEDICAL", "IN_PROGRESS", vet);
        vetToken = token(vet);

        dogType = vaccines.vaccineType("DOG", "Dại IT18");
        dose1 = vaccines.protocolDose("DOG", dogType, 1, 8, true);
        dose2Inactive = vaccines.protocolDose("DOG", dogType, 2, 12, false);
        dose3 = vaccines.protocolDose("DOG", dogType, 3, 16, true);
    }

    @AfterEach
    void tearDown() {
        vaccines.deleteDoses();
        for (long b : branches) {
            jdbc.update("DELETE FROM visits WHERE branch_id = ?", b);
        }
        vaccines.deleteCatalog();
        data.cleanUp();
    }

    // ================================================================ đường đúng

    @Test
    void returnsActiveProtocolLatestDoseFromAnotherBranchSuggestionAndOnlySellableStockOfTheVisitBranch() {
        long rabisin = vaccines.product(dogType, "B Rabisin", true);
        vaccines.lot(branch, rabisin, TODAY.plusDays(30), 4);
        vaccines.lot(branch, rabisin, TODAY.minusDays(1), 50);
        long nobivac = vaccines.product(dogType, "A Nobivac", true);
        vaccines.lot(branch, nobivac, TODAY, 2);
        vaccines.lot(otherBranch, nobivac, TODAY.plusDays(30), 99);
        long soldOut = vaccines.product(dogType, "C Hết hàng", true);
        vaccines.lot(branch, soldOut, TODAY.plusDays(30), 0);
        long discontinued = vaccines.product(dogType, "D Ngừng", false);
        vaccines.lot(branch, discontinued, TODAY.plusDays(30), 9);
        long otherTypeProduct = vaccines.product(vaccines.vaccineType("DOG", "5 bệnh IT18"), "E Khác loại", true);
        vaccines.lot(branch, otherTypeProduct, TODAY.plusDays(30), 9);

        long earlier = visit(otherBranch, "MEDICAL", "COMPLETED", foreignVet);
        long lotOther = vaccines.lot(otherBranch, rabisin, TODAY.plusDays(30), 1);
        long given = vaccines.dose(earlier, dogType, dose1, 1, rabisin, lotOther, TODAY.minusDays(30),
                TODAY.minusDays(9), foreignVet, null);

        Map<String, Object> options = data(get(vetToken, visit, dogType), HttpStatus.OK);

        assertThat(options.keySet()).containsExactlyInAnyOrder("vaccineTypeId", "protocol", "suggestedProtocolId",
                "lastDose", "products", "underMinAge");
        assertThat(options).containsEntry("vaccineTypeId", (int) dogType)
                .containsEntry("suggestedProtocolId", (int) dose3).containsEntry("underMinAge", false);
        assertThat(listOf(options.get("protocol"))).extracting(p -> p.get("protocolId"))
                .containsExactly((int) dose1, (int) dose3);
        assertThat(listOf(options.get("protocol")).get(0).keySet()).containsExactlyInAnyOrder("protocolId",
                "doseNumber", "intervalDays", "minAgeWeeks", "requiredForBoarding");
        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) options.get("lastDose");
        assertThat(last).containsEntry("vaccinationId", (int) given).containsEntry("branchId", (int) otherBranch)
                .containsEntry("doseNumber", 1).containsEntry("lotNumber", null).containsEntry("expiryDate", null)
                .containsEntry("nextDueDate", TODAY.minusDays(9).toString());
        assertThat(listOf(options.get("products"))).extracting(p -> p.get("productId"), p -> p.get("availableQuantity"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple((int) nobivac, 2),
                        org.assertj.core.groups.Tuple.tuple((int) rabisin, 4));
        assertThat(listOf(options.get("products")).get(0).keySet()).containsExactlyInAnyOrder("productId", "name",
                "availableQuantity");
    }

    @Test
    void neverVaccinatedSuggestsTheFirstActiveDoseWithNullLastDose() {
        Map<String, Object> options = data(get(vetToken, visit, dogType), HttpStatus.OK);

        assertThat(options).containsEntry("lastDose", null).containsEntry("suggestedProtocolId", (int) dose1)
                .containsEntry("products", List.of());
    }

    @Test
    void afterTheLastDoseTheLastDoseIsSuggestedAgainAndADoseOfThisVisitCounts() {
        long product = vaccines.product(dogType, "Rabisin", true);
        long lot = vaccines.lot(branch, product, TODAY.plusDays(30), 3);
        vaccines.dose(visit, dogType, dose3, 3, product, lot, TODAY, TODAY.plusDays(21), vet, null);

        Map<String, Object> options = data(get(vetToken, visit, dogType), HttpStatus.OK);

        assertThat(options).containsEntry("suggestedProtocolId", (int) dose3);
        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) options.get("lastDose");
        assertThat(last).containsEntry("visitId", (int) visit).containsEntry("doseNumber", 3);
    }

    @Test
    void underMinAgeFollowsTheSuggestedDoseAtTheClockDate() {
        setBirthDate(TODAY.minusWeeks(8).plusDays(1));
        assertThat(data(get(vetToken, visit, dogType), HttpStatus.OK)).containsEntry("underMinAge", true);

        setBirthDate(TODAY.minusWeeks(8));
        assertThat(data(get(vetToken, visit, dogType), HttpStatus.OK)).as("đúng 8 tuần").containsEntry("underMinAge",
                false);

        setBirthDate(null);
        assertThat(data(get(vetToken, visit, dogType), HttpStatus.OK)).containsEntry("underMinAge", false);
    }

    @Test
    void aTypeWithoutActiveProtocolIsStill200WithNothingToSuggest() {
        long bare = vaccines.vaccineType("DOG", "Không phác đồ IT18");
        setBirthDate(TODAY);

        Map<String, Object> options = data(get(vetToken, visit, bare), HttpStatus.OK);

        assertThat(options).containsEntry("protocol", List.of()).containsEntry("suggestedProtocolId", null)
                .containsEntry("underMinAge", false);
    }

    @Test
    void theAssigneeMayViewAWaitingOrACompletedVisit() {
        for (String status : List.of("WAITING", "COMPLETED")) {
            jdbc.update("UPDATE visits SET status = ? WHERE id = ?", status, visit);
            assertThat(get(vetToken, visit, dogType).getStatusCode()).as(status).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void nothingIsWritten() {
        long visitXmin = xmin("visits", visit);
        long petXmin = xmin("pets", pet);
        long audits = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE actor_account_id = ?", Long.class, vet);

        data(get(vetToken, visit, dogType), HttpStatus.OK);

        assertThat(xmin("visits", visit)).isEqualTo(visitXmin);
        assertThat(xmin("pets", pet)).isEqualTo(petXmin);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE actor_account_id = ?", Long.class, vet))
                .isEqualTo(audits);
    }

    // ================================================================ guard và thứ tự lỗi

    @Test
    void unauthenticatedIs401AndMustChangePasswordIsBrTk17() {
        assertThat(get(null, visit, dogType).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", vet);
        ResponseEntity<Map<String, Object>> response = get(vetToken, visit, dogType);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) response.getBody().get("message")).endsWith("(BR-TK-17)");
    }

    @Test
    void missingOrMalformedVaccineTypeIdIs400BeforeTheRoleCheck() {
        String caretaker = token(data.staff("CARETAKER", branch));
        for (String query : List.of("", "?vaccineTypeId=", "?vaccineTypeId=abc", "?vaccineTypeId=1.5")) {
            ResponseEntity<Map<String, Object>> response = exchange(caretaker,
                    "/api/visits/" + visit + "/vaccination-options" + query);
            assertError(response, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        }
        assertThat(exchange(vetToken, "/api/visits/" + visit + "/vaccination-options").getBody())
                .containsEntry("message", "Thiếu tham số 'vaccineTypeId'");
        assertError(exchange(vetToken, "/api/visits/abc/vaccination-options?vaccineTypeId=" + dogType),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
    }

    @Test
    void rolesOutsideTheContractAre403() {
        for (String role : List.of("CARETAKER", "RECEPTIONIST", "BRANCH_MANAGER")) {
            assertError(get(token(data.staff(role, branch)), visit, dogType), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertError(get(token(data.staff("SUPER_MANAGER", null)), visit, dogType), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED");
        assertError(get(token(data.customerAccount()), visit, dogType), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
    }

    @Test
    void visitGuardsComeBeforeTheVaccineType() {
        assertError(get(vetToken, Long.MAX_VALUE, dogType), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");

        ResponseEntity<Map<String, Object>> otherBranchResponse = get(token(foreignVet), visit, Long.MAX_VALUE);
        assertError(otherBranchResponse, HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(otherBranchResponse.getBody()).doesNotContainKey("data");

        long grooming = visit(branch, "GROOMING", "IN_PROGRESS", vet);
        assertError(get(vetToken, grooming, dogType), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");

        long catType = vaccines.vaccineType("CAT", "4 bệnh IT18");
        assertError(get(token(otherVet), visit, catType), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        long unassigned = visit(branch, "MEDICAL", "WAITING", null);
        assertError(get(vetToken, unassigned, dogType), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
    }

    @Test
    void vaccineTypeGuards() {
        assertError(get(vetToken, visit, Long.MAX_VALUE), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");

        long catType = vaccines.vaccineType("CAT", "4 bệnh IT18");
        jdbc.update("UPDATE vaccine_types SET is_active = false WHERE id = ?", catType);
        ResponseEntity<Map<String, Object>> wrongSpecies = get(vetToken, visit, catType);
        assertError(wrongSpecies, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) wrongSpecies.getBody().get("message")).endsWith("(BR-KB-04)");

        jdbc.update("UPDATE vaccine_types SET is_active = false WHERE id = ?", dogType);
        ResponseEntity<Map<String, Object>> discontinued = get(vetToken, visit, dogType);
        assertError(discontinued, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) discontinued.getBody().get("message")).endsWith("(BR-SP-07)");
    }

    // ================================================================ helpers

    private long branch() {
        long id = data.branch("ACTIVE");
        branches.add(id);
        return id;
    }

    /** Lượt chèn thẳng (không qua tiếp nhận): không Order, {@code assignee} null chỉ khi {@code WAITING}. */
    private long visit(long branchId, String group, String status, Long assignee) {
        return jdbc.queryForObject("""
                INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                        queue_sort_key, assignee_id, checked_in_by, checked_in_at, called_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, 'WALK_IN', ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                "VS-IT-" + UUID.randomUUID().toString().substring(0, 8), branchId, customer, pet, group, status,
                Timestamp.from(T0), assignee, vet, Timestamp.from(T0),
                "WAITING".equals(status) ? null : Timestamp.from(T0),
                "COMPLETED".equals(status) ? Timestamp.from(T0) : null);
    }

    private void setBirthDate(LocalDate birthDate) {
        jdbc.update("UPDATE pets SET birth_date = ? WHERE id = ?", birthDate, pet);
    }

    private long xmin(String table, long id) {
        return jdbc.queryForObject("SELECT xmin::text::bigint FROM " + table + " WHERE id = ?", Long.class, id);
    }

    private ResponseEntity<Map<String, Object>> get(String bearer, long visitId, long typeId) {
        return exchange(bearer, PATH.formatted(visitId) + "?vaccineTypeId=" + typeId);
    }

    private ResponseEntity<Map<String, Object>> exchange(String bearer, String path) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return http.exchange(URI.create(http.getRootUri() + path), HttpMethod.GET, new HttpEntity<>(headers), MAP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response, HttpStatus expected) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(expected);
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("errorCode", code);
    }

    private String token(long accountId) {
        return tx.execute(s -> sessions.open(accountId, "203.0.113.9", "VaccinationOptionsIT")).accessToken();
    }
}
