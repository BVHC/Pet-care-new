package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.service.ClinicCancelCause;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.care.job.InAppNotificationJob;
import com.petcare.module.care.job.NotificationOutboxJob;
import com.petcare.module.identity.service.SessionService;
import com.petcare.platform.config.TimeConfig;

import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityManagerFactory;

/**
 * Lịch hẹn#5 {@code cancelByClinic} (T25 phần 1, docs/adr/0047) trên Postgres 17 thật qua HTTP của bên phát sự kiện —
 * thêm ngày nghỉ / thu hẹp giờ (branch), đánh dấu thú mất / chuyển chủ (customer) — với listener, service, outbox và
 * worker ST20 thật (GreenMail cho email). {@code BoardingQueryApi} là {@code @MockitoBean} vì còn placeholder (D033):
 * trả rỗng / false nên không có đặt chỗ nào bị ảnh hưởng.
 * <p>
 * Chứng minh: (a) cột hủy đúng nghiệp vụ (CLINIC, không hủy muộn, không đổi số lần đổi, lý do theo nguyên nhân) và GET #4
 * khớp; (b) người nhận / kênh theo BR-TB-02 (tài khoản → IN_APP + email tài khoản, hồ sơ quầy có email → EMAIL, không có
 * gì → không gửi, tài khoản PENDING → như không có tài khoản); payload khớp {@code allowed_vars} / {@code required_vars}
 * của dòng mẫu V17, worker thay đủ biến; (c) lỗi ở lịch thứ hai rollback toàn bộ use case của bên phát; (d) race với khách
 * tự hủy / check-in / hai sự kiện cùng lịch; (e) không N+1 khi hủy 150 lịch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ClinicCancellationIT {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final String WAITING_ON_ROW = "%from appointments%for no key update%";
    private static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    private static final String TEMPLATES = "template_code IN ('APPOINTMENT_CANCELLED_BY_CLINIC', "
            + "'APPOINTMENT_CANCELLED_BY_CLINIC_APP')";

    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("test@example.com", "test-app-password"))
            .withPerMethodLifecycle(true);

    @MockitoBean
    private BoardingQueryApi boardings;

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SessionService sessions;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private InAppNotificationJob inAppJob;
    @Autowired
    private NotificationOutboxJob emailJob;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private LocalDate today;
    private LocalDate day;
    private long branch;
    private long otherBranch;
    private long medical;
    private long receptionist;
    private String managerToken;
    private String receptionistToken;

    /** Khách có tài khoản đã xác thực (hồ sơ online, {@code customers.email = null}). */
    private long account;
    private String accountEmail;
    private long online;
    private long onlinePet;
    /** Hồ sơ tại quầy có email, không tài khoản. */
    private String counterEmail;
    private long counter;
    private long counterPet;
    /** Hồ sơ tại quầy không email: không kênh nào. */
    private long silent;
    private long silentPet;

    @BeforeEach
    void setUp() {
        data = new AppointmentItSupport(jdbc);
        tx = new TransactionTemplate(transactionManager);
        today = LocalDate.now(TimeConfig.BUSINESS_ZONE);
        day = today.plusDays(20);
        branch = data.branch("ACTIVE");
        otherBranch = data.branch("ACTIVE");
        medical = data.service("MEDICAL");
        receptionist = data.staff("RECEPTIONIST", branch);
        managerToken = token(data.staff("BRANCH_MANAGER", branch));
        receptionistToken = token(receptionist);

        accountEmail = "clinic-" + UUID.randomUUID() + "@example.com";
        account = data.customerAccount(accountEmail, "ACTIVE");
        online = data.onlineCustomer(account, "Trần Thị Online");
        onlinePet = namedPet(online, "Bông");
        counterEmail = "quay-" + UUID.randomUUID() + "@example.com";
        counter = data.counterCustomer("Lê Văn Quầy", counterEmail);
        counterPet = namedPet(counter, "Mực");
        silent = data.counterCustomer("Phạm Không Email", null);
        silentPet = namedPet(silent, "Đốm");
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_clinic_cancel_fail ON appointments");
        jdbc.execute("DROP TRIGGER IF EXISTS it_clinic_cancel_fail ON notification_outbox");
        jdbc.execute("DROP FUNCTION IF EXISTS it_clinic_cancel_fail()");
        jdbc.update("DELETE FROM notification_outbox WHERE recipient_email IN (?, ?)", counterEmail, accountEmail);
        data.cleanUp();
    }

    // ---------------------------------------------------------------- ngày nghỉ (BR-LH-10, D034)

    @Test
    void holidayCancelsBookedOfThatDayAndNotifiesByChannel(CapturedOutput output) throws Exception {
        long a1 = booked(online, onlinePet, branch, day, LocalTime.of(9, 0));
        jdbc.update("UPDATE appointments SET reschedule_count = 2 WHERE id = ?", a1);
        long a2 = booked(counter, counterPet, branch, day, LocalTime.of(9, 30));
        long a3 = booked(silent, silentPet, branch, day, LocalTime.of(10, 0));
        long checkedIn = appointment(silent, namedPet(silent, "Vàng"), branch, day, LocalTime.of(10, 30), "CHECKED_IN");
        long otherDay = booked(online, onlinePet, branch, day.plusDays(1), LocalTime.of(9, 0));

        ResponseEntity<Map<String, Object>> response = addHoliday(day);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        for (long id : List.of(a1, a2, a3)) {
            assertCancelledByClinic(id, ClinicCancelCause.HOLIDAY_ADDED);
        }
        assertThat(row(a1)).containsEntry("reschedule_count", 2);
        assertThat(row(checkedIn)).containsEntry("status", "CHECKED_IN").containsEntry("cancel_source", null);
        assertThat(row(otherDay)).containsEntry("status", "BOOKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_restrictions WHERE customer_id IN (?, ?, ?)",
                Long.class, online, counter, silent)).as("BR-LH-09 không bị tính").isZero();

        // Người nhận theo BR-TB-02.
        assertThat(outboxOf(a1)).extracting(r -> r.get("channel"), r -> r.get("recipient_account_id"),
                r -> r.get("recipient_email")).containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("IN_APP", account, null),
                        org.assertj.core.groups.Tuple.tuple("EMAIL", null, accountEmail));
        assertThat(outboxOf(a2)).extracting(r -> r.get("channel"), r -> r.get("recipient_account_id"),
                r -> r.get("recipient_email")).containsExactly(
                        org.assertj.core.groups.Tuple.tuple("EMAIL", null, counterEmail));
        assertThat(outboxOf(a3)).isEmpty();
        assertThat(output).contains("APPOINTMENT_CLINIC_CANCEL_NO_CHANNEL appointmentId=" + a3);
        assertPayloadMatchesTemplateRows();
        assertThat(outboxOf(a1).get(0).get("payload").toString()).contains("Bông").contains("Trần Thị Online")
                .contains(LocalTime.of(9, 0).atDate(day).format(SLOT));

        // Response lịch hẹn (mapping M1) ≡ DB.
        Map<String, Object> view = data(get("/api/appointments/" + a1, token(account)));
        assertThat(view).containsEntry("status", "CANCELLED").containsEntry("cancelSource", "CLINIC")
                .containsEntry("lateCancel", false).containsEntry("rescheduleCount", 2)
                .containsEntry("cancelReason", ClinicCancelCause.HOLIDAY_ADDED.reason())
                .containsEntry("canReschedule", false).containsEntry("canCancel", false);
        assertThat(view.get("cancelledAt")).isNotNull();

        // ST20: thông báo trong app và email thay đủ biến của đúng lịch đó.
        inAppJob.run();
        Map<String, Object> notice = jdbc.queryForMap(
                "SELECT type, title, body, link_url FROM notifications WHERE account_id = ?", account);
        assertThat(notice).containsEntry("type", "APPOINTMENT_CANCELLED_BY_CLINIC")
                .containsEntry("title", "Phòng khám đã hủy lịch hẹn của Bông")
                .containsEntry("link_url", "/appointments");
        assertThat((String) notice.get("body")).contains("Bông").contains(LocalTime.of(9, 0).atDate(day).format(SLOT))
                .contains(ClinicCancelCause.HOLIDAY_ADDED.reason()).doesNotContain("{").doesNotContain("Mực");
        Map<String, MimeMessage> mails = sendEmails(accountEmail, counterEmail);
        assertThat(mails.get(accountEmail).getSubject()).contains("Bông");
        assertThat(mails.get(counterEmail).getSubject()).contains("Mực")
                .contains(LocalTime.of(9, 30).atDate(day).format(SLOT));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE " + TEMPLATES
                + " AND (recipient_account_id = ? OR recipient_email IN (?, ?)) AND status <> 'SENT'", Long.class,
                account, accountEmail, counterEmail)).isZero();
    }

    /** BR-CN-04: thu hẹp giờ chỉ hủy lịch rơi ra ngoài giờ mới; lý do riêng. */
    @Test
    void openingHoursReductionCancelsOnlyAffectedAppointments() {
        data.openingHours(branch, today.minusDays(30), LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(14, 0),
                LocalTime.of(19, 0));
        long afternoon = booked(counter, counterPet, branch, day, LocalTime.of(15, 0));
        long morning = booked(online, onlinePet, branch, day, LocalTime.of(9, 0));

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.PUT, "/api/branches/" + branch
                + "/opening-hours", managerToken, morningsOnly(today.plusDays(1)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertCancelledByClinic(afternoon, ClinicCancelCause.OPENING_HOURS_REDUCED);
        assertThat(row(morning)).containsEntry("status", "BOOKED");
        assertThat(outboxOf(afternoon)).hasSize(1);
        assertThat(outboxOf(morning)).isEmpty();
    }

    // ---------------------------------------------------------------- thú mất / chuyển chủ (BR-KH-05, 08)

    /** Q3: mọi lịch BOOKED của thú, kể cả lịch đã quá giờ (ST05 chưa chạy) và ở chi nhánh khác (thú dùng chung chuỗi). */
    @Test
    void receptionistMarkingDeceasedCancelsEveryBookedOfThePet() {
        long future = booked(counter, counterPet, branch, day, LocalTime.of(9, 0));
        long pastSlot = booked(counter, counterPet, otherBranch, today.minusDays(1), LocalTime.of(9, 0));
        long completed = appointment(counter, counterPet, branch, today.minusDays(3), LocalTime.of(9, 0),
                "COMPLETED");
        long otherPet = booked(counter, namedPet(counter, "Khác"), branch, day, LocalTime.of(10, 0));

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/pets/" + counterPet
                + "/mark-deceased", receptionistToken, Map.of("deceasedOn", today.toString(), "confirmed", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertCancelledByClinic(future, ClinicCancelCause.PET_DECEASED);
        assertCancelledByClinic(pastSlot, ClinicCancelCause.PET_DECEASED);
        assertThat(row(completed)).containsEntry("status", "COMPLETED");
        assertThat(row(otherPet)).containsEntry("status", "BOOKED");
        assertThat(outboxOf(future)).hasSize(1);
        assertThat(outboxOf(pastSlot)).hasSize(1);
    }

    /** Khách tự đánh dấu thú của mình (vai trò OWNER, không qua {@code BranchScope}). */
    @Test
    void ownerMarkingDeceasedCancelsAndNotifiesInApp() {
        long appointment = booked(online, onlinePet, branch, day, LocalTime.of(9, 0));

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/pets/" + onlinePet
                + "/mark-deceased", token(account), Map.of("deceasedOn", today.toString(), "confirmed", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertCancelledByClinic(appointment, ClinicCancelCause.PET_DECEASED);
        assertThat(outboxOf(appointment)).extracting(r -> r.get("channel")).containsExactlyInAnyOrder("IN_APP", "EMAIL");
    }

    /** Q8: thông báo tới chủ lúc đặt (chủ cũ); chủ cũ vẫn thấy lịch đã hủy, chủ mới không thấy. */
    @Test
    void ownerTransferCancelsBookedAndNotifiesThePreviousOwner() {
        long appointment = booked(online, onlinePet, branch, day, LocalTime.of(9, 0));
        String newEmail = "chu-moi-" + UUID.randomUUID() + "@example.com";
        long newAccount = data.customerAccount(newEmail, "ACTIVE");
        long newOwner = data.onlineCustomer(newAccount, "Chủ Mới");

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/pets/" + onlinePet
                + "/transfer-owner", receptionistToken, Map.of("newCustomerId", newOwner, "previousOwnerConfirmed",
                true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertCancelledByClinic(appointment, ClinicCancelCause.PET_OWNER_TRANSFERRED);
        assertThat(row(appointment)).containsEntry("customer_id", online);
        assertThat(outboxOf(appointment)).extracting(r -> r.get("recipient_account_id"), r -> r.get("recipient_email"))
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(account, null),
                        org.assertj.core.groups.Tuple.tuple(null, accountEmail));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE recipient_account_id = ? "
                + "OR recipient_email = ?", Long.class, newAccount, newEmail)).isZero();

        Map<String, Object> page = data(get("/api/appointments", token(account)));
        assertThat((List<Map<String, Object>>) page.get("content"))
                .filteredOn(a -> ((Number) a.get("appointmentId")).longValue() == appointment)
                .singleElement().satisfies(a -> assertThat(a).containsEntry("status", "CANCELLED")
                        .containsEntry("cancelSource", "CLINIC"));
        assertThat(get("/api/appointments/" + appointment, token(newAccount)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Tài khoản PENDING (lễ tân đặt hộ hồ sơ online chưa xác thực) = như không có tài khoản: không gửi gì. */
    @Test
    void pendingAccountGetsNothing() {
        String pendingEmail = "pending-" + UUID.randomUUID() + "@example.com";
        long pending = data.customerAccount(pendingEmail, "PENDING");
        long profile = data.onlineCustomer(pending, "Chưa Xác Thực");
        long appointment = booked(profile, namedPet(profile, "Nâu"), branch, day, LocalTime.of(11, 0));

        assertThat(addHoliday(day).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertCancelledByClinic(appointment, ClinicCancelCause.HOLIDAY_ADDED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE recipient_account_id = ? "
                + "OR recipient_email = ?", Long.class, pending, pendingEmail)).isZero();
    }

    // ---------------------------------------------------------------- rollback (C5, C6, C10)

    @Test
    void failureOnTheSecondAppointmentRollsBackTheWholeHoliday() {
        long first = booked(online, onlinePet, branch, day, LocalTime.of(9, 0));
        long second = booked(counter, counterPet, branch, day, LocalTime.of(9, 30));
        failUpdateOf(second);

        ResponseEntity<Map<String, Object>> response = addHoliday(day);

        assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(holidays(day)).isZero();
        assertThat(row(first)).containsEntry("status", "BOOKED").containsEntry("cancel_source", null);
        assertThat(row(second)).containsEntry("status", "BOOKED");
        assertThat(outboxOf(first)).isEmpty();

        dropTrigger("appointments");
        assertThat(addHoliday(day).getStatusCode()).as("không còn khóa nào kẹt").isEqualTo(HttpStatus.CREATED);
        assertCancelledByClinic(first, ClinicCancelCause.HOLIDAY_ADDED);
        assertCancelledByClinic(second, ClinicCancelCause.HOLIDAY_ADDED);
    }

    @Test
    void outboxFailureRollsBackTheDeceasedMark() {
        long first = booked(counter, counterPet, branch, day, LocalTime.of(9, 0));
        long second = booked(counter, counterPet, branch, day.plusDays(1), LocalTime.of(9, 0));
        jdbc.execute("""
                CREATE FUNCTION it_clinic_cancel_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'it outbox failure' USING ERRCODE = 'check_violation'; END $$""");
        jdbc.execute("""
                CREATE TRIGGER it_clinic_cancel_fail BEFORE INSERT ON notification_outbox FOR EACH ROW
                WHEN (NEW.template_code LIKE 'APPOINTMENT_CANCELLED_BY_CLINIC%')
                EXECUTE FUNCTION it_clinic_cancel_fail()""");

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/pets/" + counterPet
                + "/mark-deceased", receptionistToken, Map.of("deceasedOn", today.toString(), "confirmed", true));

        assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT deceased_on FROM pets WHERE id = ?", Date.class, counterPet)).isNull();
        assertThat(row(first)).containsEntry("status", "BOOKED");
        assertThat(row(second)).containsEntry("status", "BOOKED");
    }

    @Test
    void failureRollsBackTheOwnerTransferAndItsAudit() {
        long first = booked(online, onlinePet, branch, day, LocalTime.of(9, 0));
        long second = booked(online, onlinePet, branch, day.plusDays(1), LocalTime.of(9, 0));
        long newOwner = data.counterCustomer("Chủ Mới Quầy", null);
        failUpdateOf(second);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/pets/" + onlinePet
                + "/transfer-owner", receptionistToken, Map.of("newCustomerId", newOwner, "previousOwnerConfirmed",
                true));

        assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT customer_id FROM pets WHERE id = ?", Long.class, onlinePet))
                .isEqualTo(online);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'PET_OWNER_TRANSFERRED' AND entity_id = ?""",
                Long.class, onlinePet)).isZero();
        assertThat(row(first)).containsEntry("status", "BOOKED");
        assertThat(outboxOf(first)).isEmpty();
    }

    // ---------------------------------------------------------------- race (C8, C9)

    /** Khách tự hủy trong lúc ngày nghỉ chờ khóa dòng: kiểm lại sau khóa → bỏ qua, giữ lần hủy của khách. */
    @Test
    void customerCancelCommittedWhileWaitingIsKept() throws Exception {
        long mine = booked(counter, counterPet, branch, day, LocalTime.of(9, 30));
        long others = booked(online, onlinePet, branch, day, LocalTime.of(9, 0));

        List<ResponseEntity<Map<String, Object>>> results = whileRowLocked(mine, j -> j.update("""
                UPDATE appointments SET status = 'CANCELLED', cancel_source = 'CUSTOMER', late_cancel = true,
                       cancelled_at = now() WHERE id = ?""", mine), List.of(() -> addHoliday(day)), 1);

        assertThat(results.get(0).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(row(mine)).containsEntry("status", "CANCELLED").containsEntry("cancel_source", "CUSTOMER")
                .containsEntry("late_cancel", true);
        assertThat(outboxOf(mine)).isEmpty();
        assertCancelledByClinic(others, ClinicCancelCause.HOLIDAY_ADDED);
    }

    @Test
    void checkInCommittedWhileWaitingIsKept() throws Exception {
        long checkedIn = booked(counter, counterPet, branch, day, LocalTime.of(9, 30));

        List<ResponseEntity<Map<String, Object>>> results = whileRowLocked(checkedIn,
                j -> j.update("UPDATE appointments SET status = 'CHECKED_IN' WHERE id = ?", checkedIn),
                List.of(() -> addHoliday(day)), 1);

        assertThat(results.get(0).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(row(checkedIn)).containsEntry("status", "CHECKED_IN");
        assertThat(outboxOf(checkedIn)).isEmpty();
    }

    /** Thú mất ∥ ngày nghỉ trên cùng một lịch: đúng một lần hủy, một nhóm thông báo, không deadlock. */
    @Test
    void twoEventsOnTheSameAppointmentCancelItOnce() throws Exception {
        long appointment = booked(online, onlinePet, branch, day, LocalTime.of(9, 0));

        List<ResponseEntity<Map<String, Object>>> results = whileRowLocked(appointment, j -> {
        }, List.of(() -> call(HttpMethod.POST, "/api/pets/" + onlinePet + "/mark-deceased", receptionistToken,
                Map.of("deceasedOn", today.toString(), "confirmed", true)), () -> addHoliday(day)), 2);

        assertThat(results).extracting(r -> r.getStatusCode().is2xxSuccessful()).containsOnly(true);
        assertThat(row(appointment)).containsEntry("status", "CANCELLED").containsEntry("cancel_source", "CLINIC");
        assertThat(outboxOf(appointment)).hasSize(2);
    }

    // ---------------------------------------------------------------- tải (không N+1)

    /** 1 lịch và 150 lịch (150 khách khác nhau): số câu query của Hibernate chênh một hằng số nhỏ, không tỉ lệ n. */
    @Test
    void cancellingManyAppointmentsUsesBatchedLookups() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        booked(counter, counterPet, branch, day, LocalTime.of(9, 0));
        statistics.clear();
        assertThat(addHoliday(day).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long single = statistics.getQueryExecutionCount();

        LocalDate busy = day.plusDays(2);
        for (int i = 0; i < 150; i++) {
            long customer = data.counterCustomer("Khách lô " + i, null);
            booked(customer, data.pet(customer), branch, busy, LocalTime.of(9, 0));
        }
        statistics.clear();
        assertThat(addHoliday(busy).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(statistics.getQueryExecutionCount()).as("query với 150 lịch so với 1 lịch (%d)", single)
                .isLessThanOrEqualTo(single + 6);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM appointments WHERE branch_id = ? AND slot_date = ? AND cancel_source = 'CLINIC'""",
                Long.class, branch, Date.valueOf(busy))).isEqualTo(150);
    }

    // ---------------------------------------------------------------- helpers

    private long namedPet(long customer, String name) {
        long pet = data.pet(customer);
        jdbc.update("UPDATE pets SET name = ? WHERE id = ?", name, pet);
        return pet;
    }

    private long booked(long customer, long pet, long branchId, LocalDate date, LocalTime start) {
        return appointment(customer, pet, branchId, date, start, "BOOKED");
    }

    private long appointment(long customer, long pet, long branchId, LocalDate date, LocalTime start, String status) {
        return data.appointment(customer, pet, branchId, medical, "MEDICAL", date, start, status, receptionist);
    }

    private ResponseEntity<Map<String, Object>> addHoliday(LocalDate date) {
        Map<String, Object> body = new HashMap<>();
        body.put("holidayDate", date.toString());
        body.put("reason", "IT nghỉ");
        body.put("cancelAffected", true);
        return call(HttpMethod.POST, "/api/branches/" + branch + "/holidays", managerToken, body);
    }

    private static Map<String, Object> morningsOnly(LocalDate effectiveFrom) {
        List<Map<String, Object>> days = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            days.add(Map.of("dayOfWeek", d, "ranges", List.of(Map.of("open", "08:00", "close", "12:00"))));
        }
        return Map.of("effectiveFrom", effectiveFrom.toString(), "days", days, "cancelAffected", true);
    }

    private long holidays(LocalDate date) {
        return jdbc.queryForObject("SELECT count(*) FROM holidays WHERE branch_id = ? AND holiday_date = ?",
                Long.class, branch, Date.valueOf(date));
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("""
                SELECT status, cancel_source, late_cancel, cancel_reason, cancelled_at, reschedule_count, customer_id
                FROM appointments WHERE id = ?""", id);
    }

    private void assertCancelledByClinic(long id, ClinicCancelCause cause) {
        assertThat(row(id)).containsEntry("status", "CANCELLED").containsEntry("cancel_source", "CLINIC")
                .containsEntry("late_cancel", false).containsEntry("cancel_reason", cause.reason());
        assertThat(row(id).get("cancelled_at")).isNotNull();
    }

    /** Dòng outbox của mẫu hủy do phòng khám mang mã lịch hẹn {@code id} (biến {@code gio_hen} + tên thú của lịch). */
    private List<Map<String, Object>> outboxOf(long id) {
        Map<String, Object> appointment = jdbc.queryForMap("""
                SELECT a.slot_date, a.slot_start, p.name FROM appointments a JOIN pets p ON p.id = a.pet_id
                WHERE a.id = ?""", id);
        String slot = ((Date) appointment.get("slot_date")).toLocalDate()
                .atTime(((java.sql.Time) appointment.get("slot_start")).toLocalTime()).format(SLOT);
        return jdbc.queryForList("SELECT channel, recipient_account_id, recipient_email, payload::text AS payload "
                + "FROM notification_outbox WHERE " + TEMPLATES + " AND payload->>'gio_hen' = ? "
                + "AND payload->>'ten_thu_cung' = ?", slot, appointment.get("name"));
    }

    /** Khóa payload ⊆ {@code allowed_vars}, ⊇ {@code required_vars} của chính dòng mẫu V17; mọi biến bắt buộc khác rỗng. */
    private void assertPayloadMatchesTemplateRows() {
        assertThat(jdbc.queryForList("""
                SELECT o.id FROM notification_outbox o JOIN notification_templates t ON t.code = o.template_code
                WHERE o.%s AND (o.recipient_account_id = ? OR o.recipient_email IN (?, ?))
                  AND (NOT (t.allowed_vars @> (SELECT coalesce(jsonb_agg(k), '[]'::jsonb)
                                               FROM jsonb_object_keys(o.payload) k WHERE k <> 'link_url'))
                       OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(t.required_vars) v
                                  WHERE coalesce(o.payload->>v, '') = ''))""".formatted(TEMPLATES), Long.class,
                account, accountEmail, counterEmail)).as("payload lệch biến của mẫu").isEmpty();
    }

    private Map<String, MimeMessage> sendEmails(String... recipients) throws Exception {
        Set<String> wanted = Set.of(recipients);
        Map<String, MimeMessage> found = new HashMap<>();
        for (int round = 0; round < 20 && found.size() < wanted.size(); round++) {
            emailJob.run();
            for (MimeMessage message : SMTP.getReceivedMessages()) {
                for (var address : message.getRecipients(Message.RecipientType.TO)) {
                    String to = address.toString();
                    if (wanted.contains(to)) {
                        found.put(to, message);
                    }
                }
            }
        }
        assertThat(found.keySet()).containsExactlyInAnyOrder(recipients);
        return found;
    }

    private void failUpdateOf(long appointmentId) {
        jdbc.execute("""
                CREATE FUNCTION it_clinic_cancel_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.id = %d AND NEW.status = 'CANCELLED' THEN
                        RAISE EXCEPTION 'it appointment failure' USING ERRCODE = 'check_violation';
                    END IF;
                    RETURN NEW;
                END $$""".formatted(appointmentId));
        jdbc.execute("""
                CREATE TRIGGER it_clinic_cancel_fail BEFORE UPDATE ON appointments FOR EACH ROW
                EXECUTE FUNCTION it_clinic_cancel_fail()""");
    }

    private void dropTrigger(String table) {
        jdbc.execute("DROP TRIGGER IF EXISTS it_clinic_cancel_fail ON " + table);
        jdbc.execute("DROP FUNCTION IF EXISTS it_clinic_cancel_fail()");
    }

    /**
     * Giữ khóa dòng lịch hẹn trong transaction khác, gửi request lần lượt và chờ mỗi cái bị chặn ở
     * {@code FOR NO KEY UPDATE} (khuôn {@code RescheduleCancelIT.whileHeld}), chạy {@code beforeRelease} rồi commit.
     */
    private List<ResponseEntity<Map<String, Object>>> whileRowLocked(long appointmentId,
            Consumer<JdbcTemplate> beforeRelease, List<Callable<ResponseEntity<Map<String, Object>>>> requests,
            int expectedWaiting) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests.size() + 1);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM appointments WHERE id = ? FOR UPDATE", appointmentId);
                held.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                beforeRelease.accept(jdbc);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            List<Future<ResponseEntity<Map<String, Object>>>> calls = new ArrayList<>();
            for (Callable<ResponseEntity<Map<String, Object>>> request : requests) {
                calls.add(pool.submit(request));
                awaitWaiting(calls.size());
            }
            assertThat(calls).allSatisfy(call -> assertThat(call.isDone()).as("phải chờ khóa").isFalse());
            assertThat(calls).hasSize(expectedWaiting);
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            List<ResponseEntity<Map<String, Object>>> results = new ArrayList<>();
            for (Future<ResponseEntity<Map<String, Object>>> call : calls) {
                results.add(call.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitWaiting(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE ?""",
                    Long.class, WAITING_ON_ROW);
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("không thấy " + count + " request chờ khóa dòng lịch hẹn");
    }

    private String token(long accountId) {
        return tx.execute(s -> sessions.open(accountId, "203.0.113.9", "ClinicCancellationIT")).accessToken();
    }

    private ResponseEntity<Map<String, Object>> get(String path, String token) {
        return call(HttpMethod.GET, path, token, null);
    }

    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(path, method, new HttpEntity<>(body, headers), MAP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).as(String.valueOf(response.getBody())).isTrue();
        return (Map<String, Object>) response.getBody().get("data");
    }

    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status,
            String errorCode) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).containsKeys("success", "errorCode", "message", "statusCode", "timestamp",
                "traceId").containsEntry("success", false).containsEntry("errorCode", errorCode)
                .containsEntry("statusCode", status.value());
    }
}
