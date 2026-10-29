package com.petcare.module.care;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import com.petcare.module.visit.VaccinationItSupport;
import com.petcare.module.visit.job.FollowUpReminderJob;
import com.petcare.module.visit.job.VaccineOverdueJob;
import com.petcare.module.visit.job.VaccineReminderJob;
import com.petcare.support.MutableClock;

/**
 * T40.6 — đủ 6 chuyển của bảng "10. Care Task" (docs/03-state-machines.md) cùng 6 cặp sai (complete / cancel task đã
 * đóng), trên bean thật, <b>không stand-in nào</b> (IT cũ dựng task / mũi tiêm bằng SQL hoặc bắn event tay,
 * docs/testcases/T40-be.md §2.4). Mọi trạng thái nguồn đi bằng endpoint hoặc job thật: mũi tiêm ghi qua
 * {@code POST /api/visits/{id}/vaccinations}, hẹn tái khám qua bệnh án, task sinh bởi ST04 / ST18 (hồ sơ tại quầy không
 * email, có SĐT), thú mất qua {@code POST /api/pets/{id}/mark-deceased} (trả G4).
 * <p>
 * Era <b>năm 1989</b>: {@code VaccineOverdueJob} quét {@code next_due_date <= cutoff} không cận dưới, nên lớp này phải
 * cũ hơn mọi era khác ({@code VaccineOverdueIT} 1990). Chi nhánh mở 08:00–12:00 và 14:00–19:00 mọi ngày. Mốc: lượt đầu
 * ngày D0 (mũi 1 phác đồ 21 ngày → tái chủng D0+21, hẹn tái khám D0+10); D0+7 nhắc tái khám, D0+14 nhắc tái chủng, D0+28
 * quá hạn tái chủng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CareTaskFsmIT.TestBeans.class})
class CareTaskFsmIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate D0 = LocalDate.of(1989, 3, 14);
    private static final LocalDate FOLLOW_UP = D0.plusDays(10);
    private static final LocalDate DUE = D0.plusDays(21);
    private static final LocalDate LATE = D0.plusDays(28);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(at(D0, 8, 30));
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
    @Autowired
    private FollowUpReminderJob followUpJob;
    @Autowired
    private VaccineReminderJob vaccineJob;
    @Autowired
    private VaccineOverdueJob overdueJob;

    private AppointmentItSupport data;
    private VaccinationItSupport vaccines;
    private TransactionTemplate tx;
    private final List<Long> pets = new ArrayList<>();
    private long branch;
    private long medical;
    private long receptionist;
    private long vet;
    private long customer;
    private long dogType;
    private long dose1;
    private long dose2;
    private long product;
    private String receptionistToken;
    private String vetToken;

    @BeforeEach
    void setUp() {
        clock.set(at(D0, 8, 30));
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        vaccines = new VaccinationItSupport(jdbc);
        branch = data.branch("ACTIVE");
        data.openingHours(branch, LocalDate.of(1989, 1, 1), LocalTime.of(8, 0), LocalTime.of(12, 0),
                LocalTime.of(14, 0), LocalTime.of(19, 0));
        medical = data.service("MEDICAL");
        data.branchService(branch, medical, true);
        receptionist = data.staff("RECEPTIONIST", branch);
        vet = data.staff("VET", branch);
        customer = data.counterCustomer();
        dogType = vaccines.vaccineType("DOG", "Dại IT89");
        dose1 = vaccines.protocol("DOG", dogType, 1, 21, false);
        dose2 = vaccines.protocol("DOG", dogType, 2, 365, false);
        product = vaccines.product(dogType, "Rabisin", true);
        vaccines.lot(branch, product, D0.plusDays(365), 10);
        receptionistToken = token(receptionist);
        vetToken = token(vet);
    }

    @AfterEach
    void tearDown() {
        for (long pet : pets) {
            vaccines.deleteDosesOfPet(pet);
        }
        jdbc.update("DELETE FROM visit_assignments WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)",
                branch);
        jdbc.update("""
                DELETE FROM order_lines WHERE order_id IN (
                    SELECT o.id FROM orders o JOIN visits v ON v.id = o.visit_id WHERE v.branch_id = ?)""", branch);
        jdbc.update("DELETE FROM orders WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)", branch);
        vaccines.deleteDoses();
        vaccines.deleteCatalog();
        data.cleanUp();
        // không để lại mũi 1989 nào cho job quá hạn của IT khác quét (era không cận dưới)
        if (!pets.isEmpty()) {
            String in = String.join(", ", Collections.nCopies(pets.size(), "?"));
            for (String table : List.of("vaccinations", "care_tasks", "visits", "pets")) {
                String column = table.equals("pets") ? "id" : "pet_id";
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " IN (" + in + ")",
                        Long.class, pets.toArray())).as("còn sót ở %s", table).isZero();
            }
        }
        pets.clear();
    }

    // ================================================================ chuyển hợp lệ

    /**
     * #1 — ST04 (hồ sơ không email) và ST18 sinh task {@code OPEN}: tái khám (D0+7), tái chủng (D0+14), quá hạn tái
     * chủng (D0+28), mỗi loại đúng nguồn, chi nhánh của lượt, khách hiện tại; không thông báo nào. Chạy lại cả ba job
     * không sinh thêm (BR-TB-04 "tối đa 1 task quá hạn / mũi").
     */
    @Test
    void jobsOpenOneTaskPerTypeForAProfileWithoutEmail() {
        long pet = data.pet(customer);
        pets.add(pet);
        long visit = bookedVisit(pet, D0);
        long dose = vaccinate(visit, dose1);
        completeExam(visit, true);

        tasksOpened();

        assertThat(task(pet, "FOLLOW_UP_DUE")).containsEntry("medical_record_id", visit)
                .containsEntry("vaccination_id", null).containsEntry("due_date", java.sql.Date.valueOf(FOLLOW_UP));
        assertThat(task(pet, "VACCINE_DUE")).containsEntry("vaccination_id", dose)
                .containsEntry("medical_record_id", null).containsEntry("due_date", java.sql.Date.valueOf(DUE));
        assertThat(task(pet, "VACCINE_OVERDUE")).containsEntry("vaccination_id", dose)
                .containsEntry("due_date", java.sql.Date.valueOf(DUE));
        for (String type : List.of("FOLLOW_UP_DUE", "VACCINE_DUE", "VACCINE_OVERDUE")) {
            assertThat(task(pet, type)).containsEntry("status", "OPEN").containsEntry("branch_id", branch)
                    .containsEntry("customer_id", customer).containsEntry("result", null).containsEntry("note", null)
                    .containsEntry("cancel_reason", null).containsEntry("handled_by", null)
                    .containsEntry("handled_at", null);
        }
        assertThat(reminderOutbox()).as("hồ sơ không email: chỉ sinh task (BR-TB-02)").isZero();

        followUpJob.run();
        vaccineJob.run();
        overdueJob.run();

        assertThat(taskCount(pet)).isEqualTo(3);
    }

    /** #2 — lễ tân ghi kết quả gọi điện: {@code DONE} kèm kết quả, ghi chú, người xử lý; task khác không đổi. */
    @Test
    void receptionistCompletesTheCall() {
        long pet = petWithOpenTasks(true);
        long due = taskId(pet, "VACCINE_DUE");

        complete(due);

        assertThat(task(due)).containsEntry("status", "DONE").containsEntry("result", "REACHED")
                .containsEntry("note", "Đã gọi, khách hẹn tuần sau").containsEntry("cancel_reason", null)
                .containsEntry("handled_by", receptionist);
        assertThat(handledAt(due)).isEqualTo(at(LATE, 10, 0));
        assertThat(status(taskId(pet, "VACCINE_OVERDUE"))).isEqualTo("OPEN");
        assertThat(status(taskId(pet, "FOLLOW_UP_DUE"))).isEqualTo("OPEN");
    }

    /** #3 — lễ tân hủy kèm lý do: {@code CANCELLED}, lý do của lễ tân, người xử lý là lễ tân. */
    @Test
    void receptionistCancelsWithAReason() {
        long pet = petWithOpenTasks(true);
        long followUp = taskId(pet, "FOLLOW_UP_DUE");

        cancel(followUp);

        assertThat(task(followUp)).containsEntry("status", "CANCELLED").containsEntry("result", null)
                .containsEntry("note", null).containsEntry("cancel_reason", "Khách đã tự đặt lịch qua điện thoại")
                .containsEntry("handled_by", receptionist);
        assertThat(handledAt(followUp)).isEqualTo(at(LATE, 10, 0));
        assertThat(status(taskId(pet, "VACCINE_DUE"))).isEqualTo("OPEN");
    }

    /**
     * #4 (ST19, G4) — lễ tân đánh dấu thú mất qua endpoint thật: listener visit (100) rồi care (200) hủy mọi task
     * {@code OPEN} của thú với lý do hệ thống, không người xử lý; task {@code DONE} giữ nguyên dòng.
     */
    @Test
    void markingThePetDeceasedCancelsItsOpenTasksOnly() {
        long pet = petWithOpenTasks(true);
        long done = taskId(pet, "VACCINE_DUE");
        complete(done);
        String doneRow = xmin(done);
        assertThat(status(taskId(pet, "FOLLOW_UP_DUE"))).isEqualTo("OPEN");
        assertThat(status(taskId(pet, "VACCINE_OVERDUE"))).isEqualTo("OPEN");
        moveTo(at(LATE, 11, 0));

        markDeceased(pet);

        for (String type : List.of("FOLLOW_UP_DUE", "VACCINE_OVERDUE")) {
            long id = taskId(pet, type);
            assertThat(task(id)).as(type).containsEntry("status", "CANCELLED")
                    .containsEntry("cancel_reason", "Thú cưng đã mất (BR-KH-05)").containsEntry("handled_by", null)
                    .containsEntry("result", null);
            assertThat(handledAt(id)).isEqualTo(at(LATE, 11, 0));
        }
        assertThat(xmin(done)).as("hệ thống hủy task đã đóng là no-op").isEqualTo(doneRow);
        assertThat(status(done)).isEqualTo("DONE");
    }

    /**
     * #5 — ghi mũi mới cùng loại vaccine hủy task tái chủng {@code OPEN} của mũi cũ (BR-TB-03). Thú không hẹn tái khám,
     * để đặt lịch / check-in {@code MEDICAL} của lượt mới không đụng task nào: task còn {@code OPEN} tới đúng lúc ghi mũi.
     */
    @Test
    void revaccinationCancelsTheOpenTasksOfTheOlderDose() {
        long pet = petWithOpenTasks(false);
        long oldDose = jdbc.queryForObject("SELECT id FROM vaccinations WHERE pet_id = ?", Long.class, pet);
        long done = taskId(pet, "VACCINE_DUE");
        complete(done);
        String doneRow = xmin(done);
        long overdue = taskId(pet, "VACCINE_OVERDUE");

        long visit = bookedVisit(pet, LATE);
        assertThat(status(overdue)).as("đặt lịch / check-in không phải nguyên nhân").isEqualTo("OPEN");

        vaccinate(visit, dose2);

        assertThat(task(overdue)).containsEntry("status", "CANCELLED")
                .containsEntry("cancel_reason", "Đã tiêm lại (BR-TB-03)").containsEntry("handled_by", null);
        assertThat(xmin(done)).isEqualTo(doneRow);
        assertThat(jdbc.queryForObject("SELECT superseded_at FROM vaccinations WHERE id = ?", Timestamp.class, oldDose))
                .isNotNull();
        assertThat(taskCount(pet)).isEqualTo(2);
    }

    /** #6 qua Lịch hẹn#1 — lễ tân đặt lịch {@code MEDICAL}: task tái khám bị hủy, task tái chủng giữ {@code OPEN}. */
    @Test
    void bookingAMedicalAppointmentCancelsTheFollowUpTask() {
        long pet = petWithOpenTasks(true);
        long followUp = taskId(pet, "FOLLOW_UP_DUE");
        assertThat(status(followUp)).isEqualTo("OPEN");

        book(pet, LATE.plusDays(1));

        assertThat(task(followUp)).containsEntry("status", "CANCELLED")
                .containsEntry("cancel_reason", "Đã có lịch khám / lượt khám mới (BR-TB-06)")
                .containsEntry("handled_by", null);
        assertThat(status(taskId(pet, "VACCINE_DUE"))).isEqualTo("OPEN");
        assertThat(status(taskId(pet, "VACCINE_OVERDUE"))).isEqualTo("OPEN");
    }

    /** #6 qua Visit#1 — lễ tân tiếp nhận walk-in dịch vụ loại Khám: task tái khám bị hủy, task tái chủng giữ nguyên. */
    @Test
    void walkInExamCheckInCancelsTheFollowUpTask() {
        long pet = petWithOpenTasks(true);
        long followUp = taskId(pet, "FOLLOW_UP_DUE");
        assertThat(status(followUp)).isEqualTo("OPEN");

        Map<String, Object> body = new HashMap<>();
        body.put("petId", pet);
        body.put("serviceId", medical);
        created(post(receptionistToken, "/api/visits", body));

        assertThat(task(followUp)).containsEntry("status", "CANCELLED")
                .containsEntry("cancel_reason", "Đã có lịch khám / lượt khám mới (BR-TB-06)")
                .containsEntry("handled_by", null);
        assertThat(status(taskId(pet, "VACCINE_DUE"))).isEqualTo("OPEN");
        assertThat(status(taskId(pet, "VACCINE_OVERDUE"))).isEqualTo("OPEN");
    }

    // ================================================================ cặp sai → 409, không ghi gì

    /**
     * 6 cặp: complete / cancel một task đã đóng. Nguồn đi bằng API: lễ tân hoàn tất, lễ tân hủy, hệ thống hủy (thú mất).
     * Lễ tân cùng chi nhánh, body hợp lệ — 403 / 400 đứng trước 409 (docs/adr/0080 §2) không xảy ra, chỉ còn trạng thái.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"DONE", "CANCELLED_BY_DESK", "CANCELLED_BY_SYSTEM"})
    void closedTasksRejectCompleteAndCancel(String source) {
        long pet = petWithOpenTasks(false);
        long task = taskId(pet, "VACCINE_DUE");
        switch (source) {
            case "DONE" -> complete(task);
            case "CANCELLED_BY_DESK" -> cancel(task);
            case "CANCELLED_BY_SYSTEM" -> markDeceased(pet);
            default -> throw new IllegalArgumentException(source);
        }
        assertThat(status(task)).isEqualTo(source.startsWith("CANCELLED") ? "CANCELLED" : "DONE");
        Map<String, Object> before = footprint(pet, task);

        assertConflict("complete", post(receptionistToken, "/api/care-tasks/" + task + "/complete", completeBody()));
        assertConflict("cancel", post(receptionistToken, "/api/care-tasks/" + task + "/cancel", cancelBody()));

        assertThat(footprint(pet, task)).isEqualTo(before);
    }

    /** Dấu "không ghi gì": phiên bản dòng, các cột xử lý và số task của thú. */
    private Map<String, Object> footprint(long pet, long task) {
        Map<String, Object> row = new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT xmin::text AS version, status, result, note, cancel_reason, handled_by, handled_at
                FROM care_tasks WHERE id = ?""", task));
        row.put("tasks", taskCount(pet));
        return row;
    }

    // ================================================================ helpers — luồng

    /**
     * Thú mới có lượt khám D0 kèm mũi 1 (và hẹn tái khám nếu {@code followUp}) đã hoàn tất, rồi các job sinh task tới
     * D0+28; đồng hồ dừng ở D0+28 10:00.
     */
    private long petWithOpenTasks(boolean followUp) {
        long pet = data.pet(customer);
        pets.add(pet);
        long visit = bookedVisit(pet, D0);
        vaccinate(visit, dose1);
        completeExam(visit, followUp);
        tasksOpened();
        assertThat(taskCount(pet)).isEqualTo(followUp ? 3 : 2);
        return pet;
    }

    /** ST04 tái khám (D0+7), ST04 tái chủng (D0+14), ST18 quá hạn (D0+28), mỗi lần 10:00. */
    private void tasksOpened() {
        clock.set(at(D0.plusDays(7), 10, 0));
        followUpJob.run();
        clock.set(at(D0.plusDays(14), 10, 0));
        vaccineJob.run();
        moveTo(at(LATE, 10, 0));
        overdueJob.run();
    }

    /** Lễ tân đặt khung 09:30 ngày {@code day} (lúc 08:30), check-in 09:10, gán VET, VET gọi lượt. */
    private long bookedVisit(long pet, LocalDate day) {
        moveTo(at(day, 8, 30));
        long appointment = book(pet, day);
        moveTo(at(day, 9, 10));
        Map<String, Object> body = new HashMap<>();
        body.put("petId", pet);
        body.put("appointmentId", appointment);
        long visit = id(created(post(receptionistToken, "/api/visits", body)), "visitId");
        ok(post(receptionistToken, "/api/visits/" + visit + "/assign", Map.of("assigneeId", vet)));
        ok(post(vetToken, "/api/visits/" + visit + "/call", null));
        return visit;
    }

    private long book(long pet, LocalDate day) {
        Map<String, Object> booking = new LinkedHashMap<>();
        booking.put("petId", pet);
        booking.put("branchId", branch);
        booking.put("serviceId", medical);
        booking.put("slotDate", day.toString());
        booking.put("slotStart", "09:30");
        return id(created(post(receptionistToken, "/api/appointments", booking)), "appointmentId");
    }

    /** Ghi mũi tiêm qua visit-v1 #19; trả id mũi. */
    private long vaccinate(long visit, long protocol) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vaccineTypeId", dogType);
        body.put("protocolId", protocol);
        body.put("productId", product);
        created(post(vetToken, "/api/visits/" + visit + "/vaccinations", body));
        return jdbc.queryForObject("SELECT max(id) FROM vaccinations WHERE visit_id = ?", Long.class, visit);
    }

    /** Ghi chẩn đoán (kèm hẹn tái khám D0+10 nếu cần, BR-KB-06) rồi hoàn tất (Visit#5: bệnh án khóa). */
    private void completeExam(long visit, boolean followUp) {
        Map<String, Object> record = new HashMap<>();
        record.put("examination", "Khám tổng quát");
        record.put("diagnosis", "Khỏe mạnh");
        if (followUp) {
            record.put("followUpDate", FOLLOW_UP.toString());
        }
        ok(exchange(HttpMethod.PUT, vetToken, "/api/visits/" + visit + "/medical-record", record));
        ok(post(vetToken, "/api/visits/" + visit + "/complete", null));
    }

    private void complete(long task) {
        ok(post(receptionistToken, "/api/care-tasks/" + task + "/complete", completeBody()));
    }

    private void cancel(long task) {
        ok(post(receptionistToken, "/api/care-tasks/" + task + "/cancel", cancelBody()));
    }

    private void markDeceased(long pet) {
        ok(post(receptionistToken, "/api/pets/" + pet + "/mark-deceased",
                Map.of("deceasedOn", LocalDate.ofInstant(clock.instant(), VN).toString(), "confirmed", true)));
    }

    private static Map<String, Object> completeBody() {
        return Map.of("result", "REACHED", "note", "Đã gọi, khách hẹn tuần sau");
    }

    private static Map<String, Object> cancelBody() {
        return Map.of("reason", "Khách đã tự đặt lịch qua điện thoại");
    }

    // ================================================================ helpers — dữ liệu

    private Map<String, Object> task(long pet, String type) {
        return jdbc.queryForMap("SELECT * FROM care_tasks WHERE pet_id = ? AND task_type = ?", pet, type);
    }

    private Map<String, Object> task(long id) {
        return jdbc.queryForMap("SELECT * FROM care_tasks WHERE id = ?", id);
    }

    private long taskId(long pet, String type) {
        return jdbc.queryForObject("SELECT id FROM care_tasks WHERE pet_id = ? AND task_type = ?", Long.class, pet,
                type);
    }

    private String status(long task) {
        return jdbc.queryForObject("SELECT status FROM care_tasks WHERE id = ?", String.class, task);
    }

    private String xmin(long task) {
        return jdbc.queryForObject("SELECT xmin::text FROM care_tasks WHERE id = ?", String.class, task);
    }

    private Instant handledAt(long task) {
        return jdbc.queryForObject("SELECT handled_at FROM care_tasks WHERE id = ?", Timestamp.class, task).toInstant();
    }

    private long taskCount(long pet) {
        return jdbc.queryForObject("SELECT count(*) FROM care_tasks WHERE pet_id = ?", Long.class, pet);
    }

    /** Thông báo nhắc tái chủng / tái khám của chi nhánh này (tên chi nhánh là UUID, nằm trong payload). */
    private long reminderOutbox() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox o JOIN branches b ON b.name = o.payload ->> 'chi_nhanh'
                WHERE b.id = ? AND o.template_code IN ('VACCINE_REMINDER', 'VACCINE_REMINDER_APP', 'FOLLOW_UP_REMINDER',
                                                       'FOLLOW_UP_REMINDER_APP')""", Long.class, branch);
    }

    /** Dời đồng hồ rồi mở phiên mới: phiên cũ hết hạn theo cùng {@code Clock} khi nhảy ngày (ADR-0003). */
    private void moveTo(Instant instant) {
        clock.set(instant);
        receptionistToken = token(receptionist);
        vetToken = token(vet);
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "CareTaskFsmIT")).accessToken();
    }

    private static Instant at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(VN).toInstant();
    }

    // ================================================================ helpers — HTTP

    private ResponseEntity<Map<String, Object>> post(String bearer, String path, Object body) {
        return exchange(HttpMethod.POST, bearer, path, body);
    }

    private ResponseEntity<Map<String, Object>> exchange(HttpMethod method, String bearer, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(URI.create(http.getRootUri() + path), method, new HttpEntity<>(body, headers), MAP);
    }

    private static void ok(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
    }

    private static Map<String, Object> created(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.CREATED);
        return map(response.getBody().get("data"));
    }

    private static void assertConflict(String operation, ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as("%s → %s", operation, response.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("success", false)
                .containsEntry("errorCode", "INVALID_STATE_TRANSITION").containsEntry("statusCode", 409);
    }

    private static long id(Map<String, Object> data, String key) {
        return ((Number) data.get(key)).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
