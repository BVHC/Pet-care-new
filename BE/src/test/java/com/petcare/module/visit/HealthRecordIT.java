package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
import org.springframework.http.MediaType;
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
 * visit-v1 #21 {@code GET /api/pets/{petId}/health-record} trên Postgres thật (docs/adr/0108): hình dạng JSON, chỉ lượt
 * {@code COMPLETED}, thứ tự mới nhất trước, khách không nhận ghi chú nội bộ và bản bổ sung nội bộ (BR-KH-07), mũi tiêm
 * (superseded, sắp tới), ngày tái khám gần nhất, thứ tự lỗi 403 vai trò → 400 BR-TK-19 → 404 → 403 không phải chủ.
 * Đồng hồ {@link MutableClock} ở năm 2011 (hôm nay = 2011-04-06).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, HealthRecordIT.TestBeans.class})
class HealthRecordIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2011, 4, 6);
    private static final String PATH = "/api/pets/%d/health-record";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(TODAY.atTime(9, 0).atZone(VN).toInstant());
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
    private long branchA;
    private long branchB;
    private long receptionist;
    private long vet;
    private long caretaker;
    private long manager;
    private long admin;
    private long ownerAccount;
    private long owner;
    private long strangerAccount;
    private long stranger;
    private long pet;
    private String ownerToken;
    private String strangerToken;
    private String receptionistToken;
    private String vetToken;
    private String caretakerToken;
    private String managerToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        clock.set(TODAY.atTime(9, 0).atZone(VN).toInstant());
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        vaccines = new VaccinationItSupport(jdbc);
        branchA = activeBranch();
        branchB = activeBranch();
        receptionist = data.staff("RECEPTIONIST", branchA);
        vet = data.staff("VET", branchA);
        caretaker = data.staff("CARETAKER", branchA);
        manager = data.staff("BRANCH_MANAGER", branchA);
        admin = data.staff("ADMIN", null);
        ownerAccount = data.customerAccount();
        owner = data.onlineCustomer(ownerAccount, "Chủ hồ sơ " + UUID.randomUUID());
        strangerAccount = data.customerAccount();
        stranger = data.onlineCustomer(strangerAccount, "Khách khác " + UUID.randomUUID());
        pet = data.pet(owner);
        ownerToken = token(ownerAccount);
        strangerToken = token(strangerAccount);
        receptionistToken = token(receptionist);
        vetToken = token(vet);
        caretakerToken = token(caretaker);
        managerToken = token(manager);
        adminToken = token(admin);
    }

    @AfterEach
    void tearDown() {
        vaccines.deleteDoses();
        for (long b : branches) {
            jdbc.update("""
                    DELETE FROM medical_record_addenda WHERE medical_record_id IN (
                        SELECT id FROM visits WHERE branch_id = ?)""", b);
            jdbc.update("DELETE FROM prescription_items WHERE medical_record_id IN (SELECT id FROM visits WHERE branch_id = ?)",
                    b);
            jdbc.update("DELETE FROM medical_records WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)", b);
            jdbc.update("""
                    DELETE FROM order_lines WHERE order_id IN (
                        SELECT o.id FROM orders o JOIN visits v ON v.id = o.visit_id WHERE v.branch_id = ?)""", b);
            jdbc.update("DELETE FROM orders WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)", b);
            jdbc.update("DELETE FROM visits WHERE branch_id = ?", b);
        }
        vaccines.deleteCatalog();
        data.cleanUp();
    }

    // ================================================================ hình dạng và nội dung

    @Test
    void ownerGetsCompletedVisitsNewestFirstWithoutTheInternalNoteAndInternalAddenda() {
        long v1 = visit(branchA, "MEDICAL", "COMPLETED", day(4, 1));
        record(v1, "Sốt 39,5°C", "Viêm dạ dày ruột", "Kháng sinh 5 ngày", "Chủ khó tính", TODAY.plusDays(14));
        long[] rx = rxItem(v1, "Amoxicillin", 3);
        long publicNote = addendum(v1, "Kết quả xét nghiệm máu bình thường", false);
        addendum(v1, "Gọi trước khi đổi thuốc", true);
        long v2 = visit(branchB, "GROOMING", "COMPLETED", day(4, 5));
        visit(branchA, "MEDICAL", "WAITING", day(4, 6));
        long v4 = visit(branchA, "MEDICAL", "COMPLETED", day(3, 1));

        Map<String, Object> record = data(get(ownerToken, pet), HttpStatus.OK);

        assertThat(record.keySet()).containsExactlyInAnyOrder("petId", "visits", "vaccinations", "upcomingVaccinations",
                "upcomingFollowUpDate");
        assertThat(record).containsEntry("petId", (int) pet);
        List<Map<String, Object>> visits = listOf(record.get("visits"));
        assertThat(visits).extracting(v -> ((Number) v.get("visitId")).longValue()).as("COMPLETED, mới nhất trước")
                .containsExactly(v2, v1, v4);
        assertThat(visits).extracting(v -> v.get("visitDate")).containsExactly("2011-04-05", "2011-04-01", "2011-03-01");

        Map<String, Object> first = visits.get(1);
        assertThat(first.keySet()).as("khách không có internalNote").containsExactlyInAnyOrder("visitId", "visitDate",
                "branchName", "serviceGroup", "examination", "diagnosis", "treatmentPlan", "followUpDate",
                "prescriptions", "addenda");
        assertThat(first).containsEntry("branchName", name(branchA)).containsEntry("serviceGroup", "MEDICAL")
                .containsEntry("examination", "Sốt 39,5°C").containsEntry("diagnosis", "Viêm dạ dày ruột")
                .containsEntry("treatmentPlan", "Kháng sinh 5 ngày")
                .containsEntry("followUpDate", TODAY.plusDays(14).toString());
        List<Map<String, Object>> prescriptions = listOf(first.get("prescriptions"));
        assertThat(prescriptions).hasSize(1);
        assertThat(prescriptions.get(0)).containsEntry("itemId", (int) rx[0]).containsEntry("quantity", 3);
        assertThat((String) prescriptions.get(0).get("productName")).startsWith("Amoxicillin");
        List<Map<String, Object>> addenda = listOf(first.get("addenda"));
        assertThat(addenda).as("chỉ bản bổ sung không nội bộ").hasSize(1);
        assertThat(addenda.get(0)).containsEntry("addendumId", (int) publicNote)
                .containsEntry("content", "Kết quả xét nghiệm máu bình thường").containsEntry("isInternal", false);
        assertThat((String) addenda.get(0).get("createdByName")).isNotBlank();

        Map<String, Object> grooming = visits.get(0);
        assertThat(grooming).containsEntry("serviceGroup", "GROOMING").containsEntry("branchName", name(branchB));
        assertThat(grooming).containsKeys("examination", "diagnosis", "treatmentPlan", "followUpDate")
                .doesNotContainKey("internalNote");
        assertThat(grooming.get("examination")).isNull();
        assertThat(grooming.get("prescriptions")).isEqualTo(List.of());
        assertThat(grooming.get("addenda")).isEqualTo(List.of());
        assertThat(visits.get(2).get("diagnosis")).as("MEDICAL hoàn tất mà chưa ghi bệnh án").isNull();
    }

    @Test
    void staffGetTheInternalNoteAndEveryAddendum() {
        long visit = visit(branchA, "MEDICAL", "COMPLETED", day(4, 1));
        record(visit, "khám", "dx", "tp", "Chủ khó tính", null);
        addendum(visit, "công khai", false);
        addendum(visit, "nội bộ", true);

        for (String bearer : List.of(vetToken, receptionistToken, caretakerToken)) {
            Map<String, Object> entry = listOf(data(get(bearer, pet), HttpStatus.OK).get("visits")).get(0);
            assertThat(entry).containsEntry("internalNote", "Chủ khó tính");
            assertThat(listOf(entry.get("addenda"))).extracting(a -> a.get("content")).containsExactly("công khai",
                    "nội bộ");
        }
    }

    @Test
    void vaccinationsAreNewestFirstWithTheSupersededFlagAndUpcomingKeepsOnlyUnsupersededOnes() {
        long visit = visit(branchA, "MEDICAL", "COMPLETED", day(3, 10));
        long rabies = vaccines.vaccineType("DOG", "Dại HR");
        long rabiesProtocol = vaccines.protocol("DOG", rabies, 1, 365, false);
        long rabiesProduct = vaccines.product(rabies, "Rabisin", true);
        long rabiesLot = vaccines.lot(branchA, rabiesProduct);
        long parvo = vaccines.vaccineType("DOG", "Parvo HR");
        long parvoProtocol = vaccines.protocol("DOG", parvo, 1, 180, false);
        long parvoProduct = vaccines.product(parvo, "Nobivac", true);
        long parvoLot = vaccines.lot(branchA, parvoProduct);
        long old = vaccines.dose(visit, rabies, rabiesProtocol, 1, rabiesProduct, rabiesLot, LocalDate.of(2011, 1, 10),
                LocalDate.of(2012, 1, 10), vet, Instant.parse("2011-03-10T02:00:00Z"));
        long newer = vaccines.dose(visit, rabies, rabiesProtocol, 1, rabiesProduct, rabiesLot, LocalDate.of(2011, 3, 10),
                LocalDate.of(2012, 3, 10), vet, null);
        long other = vaccines.dose(visit, parvo, parvoProtocol, 1, parvoProduct, parvoLot, LocalDate.of(2011, 2, 1),
                LocalDate.of(2011, 8, 1), vet, null);

        Map<String, Object> record = data(get(ownerToken, pet), HttpStatus.OK);

        List<Map<String, Object>> doses = listOf(record.get("vaccinations"));
        assertThat(doses).extracting(d -> ((Number) d.get("vaccinationId")).longValue()).containsExactly(newer, other,
                old);
        assertThat(doses).extracting(d -> d.get("superseded")).containsExactly(false, false, true);
        assertThat(doses.get(0)).containsEntry("administeredOn", "2011-03-10").containsEntry("nextDueDate", "2012-03-10");
        assertThat((String) doses.get(0).get("vaccineTypeName")).startsWith("Dại HR");
        List<Map<String, Object>> upcoming = listOf(record.get("upcomingVaccinations"));
        assertThat(upcoming).as("mũi chưa bị thay thế, ngày tái chủng gần nhất trước").hasSize(2);
        assertThat(upcoming.get(0)).containsEntry("vaccineTypeId", (int) parvo).containsEntry("nextDueDate", "2011-08-01");
        assertThat(upcoming.get(1)).containsEntry("vaccineTypeId", (int) rabies).containsEntry("nextDueDate",
                "2012-03-10");
        assertThat(upcoming.get(1).keySet()).containsExactlyInAnyOrder("vaccineTypeId", "vaccineTypeName", "nextDueDate");
    }

    @Test
    void upcomingFollowUpDateIsTheEarliestOneNotBeforeToday() {
        for (LocalDate followUp : List.of(TODAY.minusDays(4), TODAY.plusDays(20), TODAY, TODAY.plusDays(4))) {
            long visit = visit(branchA, "MEDICAL", "COMPLETED", day(4, 1));
            record(visit, "khám", "dx", "tp", null, followUp);
        }

        Map<String, Object> record = data(get(ownerToken, pet), HttpStatus.OK);

        assertThat(record).containsEntry("upcomingFollowUpDate", TODAY.toString());
    }

    @Test
    void noFutureFollowUpAndNoHistoryGiveNullAndEmptyLists() {
        Map<String, Object> empty = data(get(ownerToken, pet), HttpStatus.OK);
        assertThat(empty.get("visits")).isEqualTo(List.of());
        assertThat(empty.get("vaccinations")).isEqualTo(List.of());
        assertThat(empty.get("upcomingVaccinations")).isEqualTo(List.of());
        assertThat(empty).containsKey("upcomingFollowUpDate");
        assertThat(empty.get("upcomingFollowUpDate")).isNull();

        long visit = visit(branchA, "MEDICAL", "COMPLETED", day(4, 1));
        record(visit, "khám", "dx", "tp", null, TODAY.minusDays(1));
        assertThat(data(get(ownerToken, pet), HttpStatus.OK).get("upcomingFollowUpDate")).isNull();
    }

    // ================================================================ phân quyền và thứ tự lỗi

    @Test
    void aCustomerThatIsNotTheCurrentOwnerGets403IncludingTheFormerOwnerAfterATransfer() {
        visit(branchA, "MEDICAL", "COMPLETED", day(4, 1));

        assertScopeDenied(get(strangerToken, pet));
        jdbc.update("UPDATE pets SET customer_id = ? WHERE id = ?", stranger, pet);
        assertScopeDenied(get(ownerToken, pet));
        data(get(strangerToken, pet), HttpStatus.OK);
    }

    @Test
    void anUnknownPetIs404ForACustomerAndForStaff() {
        assertError(get(ownerToken, 987654321L), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(get(vetToken, 987654321L), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    @Test
    void aProfileWaitingForTheLinkDecisionIs400BrTk19BeforeAnyPetLookup() {
        jdbc.update("UPDATE customers SET link_decision_pending = true WHERE id = ?", owner);

        for (long petId : List.of(pet, 987654321L)) {
            ResponseEntity<Map<String, Object>> response = get(ownerToken, petId);
            assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
            assertThat((String) response.getBody().get("message")).endsWith("(BR-TK-19)");
        }
        data(get(vetToken, pet), HttpStatus.OK);
    }

    @Test
    void rolesOutsideTheContractAre403AndNoTokenIs401() {
        assertThat(get(managerToken, pet).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(adminToken, pet).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertError(get(null, pet), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void aDeceasedPetKeepsItsHistory() {
        visit(branchA, "MEDICAL", "COMPLETED", day(4, 1));
        jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", TODAY.minusDays(1), pet);

        assertThat(listOf(data(get(ownerToken, pet), HttpStatus.OK).get("visits"))).hasSize(1);
    }

    // ================================================================ dữ liệu

    private long activeBranch() {
        long id = data.branch("ACTIVE");
        branches.add(id);
        data.openingHours(id, LocalDate.of(2000, 1, 1), LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(14, 0),
                LocalTime.of(19, 0));
        return id;
    }

    private String name(long branch) {
        return jdbc.queryForObject("SELECT name FROM branches WHERE id = ?", String.class, branch);
    }

    private static Instant day(int month, int dayOfMonth) {
        return LocalDate.of(2011, month, dayOfMonth).atTime(9, 0).atZone(VN).toInstant();
    }

    /** Lượt của {@code pet}; {@code COMPLETED} có VET phụ trách (CHECK {@code ck_visits_assignee}). */
    private long visit(long branch, String group, String status, Instant checkedInAt) {
        Timestamp at = Timestamp.from(checkedInAt);
        return jdbc.queryForObject("""
                INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                        queue_sort_key, assignee_id, checked_in_by, checked_in_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, 'WALK_IN', ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                "VS-HR-" + UUID.randomUUID().toString().substring(0, 8), branch, owner, pet, group, status, at,
                "WAITING".equals(status) ? null : vet, receptionist, at,
                "COMPLETED".equals(status) ? Timestamp.from(checkedInAt.plusSeconds(1800)) : null);
    }

    private void record(long visit, String examination, String diagnosis, String treatmentPlan, String internalNote,
            LocalDate followUp) {
        jdbc.update("""
                INSERT INTO medical_records (visit_id, examination, diagnosis, treatment_plan, internal_note,
                        follow_up_date, last_edited_by, locked_at) VALUES (?, ?, ?, ?, ?, ?, ?, now())""", visit,
                examination, diagnosis, treatmentPlan, internalNote, followUp, vet);
    }

    private long addendum(long visit, String content, boolean internal) {
        return jdbc.queryForObject("""
                INSERT INTO medical_record_addenda (medical_record_id, content, is_internal, created_by)
                VALUES (?, ?, ?, ?) RETURNING id""", Long.class, visit, content, internal, vet);
    }

    private long[] rxItem(long visit, String drugName, int quantity) {
        long product = vaccines.prescriptionDrug(drugName);
        long line = vaccines.drugLine(visit, product, quantity, vet);
        long item = vaccines.prescriptionItem(visit, product, quantity, "Ngày 2 lần " + drugName, line);
        return new long[] {item, product};
    }

    // ================================================================ HTTP

    private ResponseEntity<Map<String, Object>> get(String bearer, long petId) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(URI.create(http.getRootUri() + PATH.formatted(petId)), HttpMethod.GET,
                new HttpEntity<>(null, headers), MAP);
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

    private static void assertScopeDenied(ResponseEntity<Map<String, Object>> response) {
        assertError(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
    }

    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("errorCode", code);
    }

    private String token(long accountId) {
        return tx.execute(s -> sessions.open(accountId, "203.0.113.9", "HealthRecordIT")).accessToken();
    }
}
