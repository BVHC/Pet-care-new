package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import com.petcare.module.appointment.api.AppointmentApi;
import com.petcare.module.appointment.job.AppointmentReminderJob;
import com.petcare.module.appointment.job.NoShowJob;
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.appointment.service.AppointmentReminderService;
import com.petcare.module.appointment.service.AppointmentReminderService.ReminderResult;
import com.petcare.module.appointment.service.ReminderWindow;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.care.job.InAppNotificationJob;
import com.petcare.module.care.job.NotificationOutboxJob;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.support.MutableClock;

import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;

/**
 * ST03 nhắc lịch hẹn (T25 phần 4, BR-LH-12, docs/adr/0051) trên Postgres 17 thật, với bean thật của customer / catalog /
 * branch / care (worker ST20 + GreenMail). Đồng hồ {@link MutableClock} ở <b>năm 2001</b> (NoShowIT dùng năm 2000) và
 * mọi lịch của test này ở quanh 01/06/2001, nên cửa sổ quét của job không đụng lịch của IT khác. {@code BoardingQueryApi}
 * là mock (D033) — chỉ hủy muộn / ST05 / ngày nghỉ gọi tới, trả 0 / rỗng.
 * <p>
 * Chứng minh theo kế hoạch: truy vết nghiệp vụ R1–R12 (dòng nào được nhắc), người nhận BR-TB-02, payload ⊆ / ⊇ biến của
 * dòng mẫu V18 và ST20 gửi được; nguyên tử (outbox lỗi → không đánh dấu); ma trận đồng thời C1–C7 theo cả hai thứ tự:
 * "luồng kia giữ dòng" dựng bằng trigger tạm làm UPDATE của luồng đó chờ một advisory lock do test giữ (luồng đó đã khóa
 * dòng); "ST03 giữ dòng" dựng bằng {@code FOR UPDATE} trên hai dòng mẫu nhắc — INSERT outbox của ST03 (sau khi đã khóa
 * dòng và đánh dấu) chờ khóa {@code FOR KEY SHARE} của khóa ngoại. Cả hai trạng thái chờ được xác nhận qua
 * {@code pg_stat_activity}. EXPLAIN trên 20 000 dòng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AppointmentReminderIT.TestBeans.class})
@ExtendWith(OutputCaptureExtension.class)
class AppointmentReminderIT {

    private static final LocalDate DAY = LocalDate.of(2001, 6, 1);
    private static final LocalDate TOMORROW = DAY.plusDays(1);
    /** 01/06/2001 09:00 giờ Việt Nam. */
    private static final Instant T0 = vn(DAY, LocalTime.of(9, 0));
    private static final String REMINDER_TEMPLATES = "template_code IN ('APPOINTMENT_REMINDER', "
            + "'APPOINTMENT_REMINDER_APP')";
    private static final String WAITING_ON_ROW = "%from appointments%for no key update%";
    private static final String WAITING_ON_OUTBOX = "%insert into notification_outbox%";
    private static final String WAITING_IN_PAUSED_UPDATE = "%update appointments%";
    private static final int PAUSE_NAMESPACE = 4242;
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
    private PlatformTransactionManager transactionManager;
    @Autowired
    private SessionService sessions;
    @Autowired
    private MutableClock clock;
    @Autowired
    private AppointmentReminderJob job;
    @Autowired
    private AppointmentReminderService service;
    @Autowired
    private NoShowJob noShowJob;
    @Autowired
    private AppointmentApi appointmentApi;
    @Autowired
    private InAppNotificationJob inAppJob;
    @Autowired
    private NotificationOutboxJob emailJob;
    @Autowired
    private SystemConfigService configs;
    @Autowired
    private ConfigurableListableBeanFactory beans;

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private long branch;
    private long medical;
    private long receptionist;
    private String receptionistToken;
    private String managerToken;

    /** Khách có tài khoản đã xác thực (hồ sơ online, {@code customers.email = null}). */
    private long account;
    private String accountEmail;
    private long online;
    /** Hồ sơ tại quầy có email. */
    private String counterEmail;
    private long counter;
    /** Hồ sơ tại quầy không email: không kênh nào. */
    private long silent;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        branch = data.branch("ACTIVE");
        medical = data.service("MEDICAL");
        data.openingHours(branch, LocalDate.of(2000, 1, 1), LocalTime.of(8, 0), LocalTime.of(17, 0), null, null);
        data.branchService(branch, medical, true);
        receptionist = data.staff("RECEPTIONIST", branch);
        receptionistToken = token(receptionist);
        managerToken = token(data.staff("BRANCH_MANAGER", branch));

        accountEmail = "remind-" + UUID.randomUUID() + "@example.com";
        account = data.customerAccount(accountEmail, "ACTIVE");
        online = data.onlineCustomer(account, "Trần Thị Online");
        counterEmail = "remind-quay-" + UUID.randomUUID() + "@example.com";
        counter = data.counterCustomer("Lê Văn Quầy", counterEmail);
        silent = data.counterCustomer("Phạm Không Email", null);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS reminder_it_pause ON appointments");
        jdbc.execute("DROP FUNCTION IF EXISTS reminder_it_pause()");
        jdbc.execute("DROP TRIGGER IF EXISTS reminder_it_fail ON notification_outbox");
        jdbc.execute("DROP FUNCTION IF EXISTS reminder_it_fail()");
        jdbc.update("DELETE FROM notification_outbox WHERE recipient_email IN (?, ?)", counterEmail, accountEmail);
        data.cleanUp();
    }

    // ================================================================ R — dòng nào được nhắc

    /** R1 + C (tài khoản): đúng một bộ IN_APP + EMAIL tới email tài khoản; chỉ hai cột đổi (Q7). */
    @Test
    void dueBookedOfAccountIsRemindedOnceAndOnlyTwoColumnsChange() {
        long id = booked(online, DAY, LocalTime.of(14, 0));
        String before = rowWithoutReminder(id);

        job.run();

        assertThat(outbox(id)).extracting(r -> r.get("channel"), r -> r.get("recipient_account_id"),
                r -> r.get("recipient_email"), r -> r.get("link_url")).containsExactlyInAnyOrder(
                        tuple("IN_APP", account, null, "/appointments"), tuple("EMAIL", null, accountEmail, null));
        assertThat(rowWithoutReminder(id)).isEqualTo(before);
        Map<String, Object> marks = jdbc.queryForMap("SELECT reminded_at, updated_at, status FROM appointments WHERE id = ?",
                id);
        assertThat(((Timestamp) marks.get("reminded_at")).toInstant()).isEqualTo(T0);
        assertThat(marks.get("updated_at")).isEqualTo(marks.get("reminded_at"));
        assertThat(marks).containsEntry("status", "BOOKED");
    }

    /** R2: không trạng thái nào ngoài BOOKED được nhắc (mọi nguồn hủy, cả hủy muộn). */
    @Test
    void nonBookedRowsAreNeverReminded() {
        List<Long> ids = new ArrayList<>();
        ids.add(appointment(counter, DAY, LocalTime.of(10, 0), "CHECKED_IN"));
        ids.add(appointment(counter, DAY, LocalTime.of(10, 30), "COMPLETED"));
        ids.add(appointment(counter, DAY, LocalTime.of(11, 0), "NO_SHOW"));
        int minute = 0;
        for (String source : List.of("CUSTOMER", "STAFF", "CLINIC")) {
            for (boolean late : List.of(false, true)) {
                long id = appointment(counter, DAY, LocalTime.of(12, minute), "CANCELLED");
                jdbc.update("UPDATE appointments SET cancel_source = ?, late_cancel = ?, cancelled_at = ? WHERE id = ?",
                        source, late, Timestamp.from(T0.minusSeconds(60)), id);
                ids.add(id);
                minute += 5;
            }
        }

        job.run();

        for (long id : ids) {
            assertThat(remindedAt(id)).as("lịch %d", id).isNull();
            assertThat(outbox(id)).isEmpty();
        }
    }

    /** R3: chạy lại không gửi thêm, không ghi lại. */
    @Test
    void rerunSendsNothing() {
        long id = booked(counter, DAY, LocalTime.of(15, 0));
        job.run();
        String after = jdbc.queryForObject("SELECT to_jsonb(a)::text FROM appointments a WHERE id = ?", String.class, id);
        clock.advance(java.time.Duration.ofMinutes(5));

        job.run();

        assertThat(outbox(id)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT to_jsonb(a)::text FROM appointments a WHERE id = ?", String.class, id))
                .isEqualTo(after);
    }

    /** R4: đã nhắc rồi đổi khung (qua API #5) → lượt sau nhắc khung mới. */
    @Test
    void rescheduledAppointmentIsRemindedAgainForTheNewSlot() {
        long id = booked(counter, DAY, LocalTime.of(15, 0));
        job.run();
        assertThat(outbox(id)).extracting(r -> r.get("gio_hen")).containsExactly("15:00 01/06/2001");

        ResponseEntity<Map<String, Object>> response = reschedule(id, DAY, "16:00");
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(remindedAt(id)).isNull();

        job.run();

        assertThat(outbox(id)).extracting(r -> r.get("gio_hen"))
                .containsExactlyInAnyOrder("15:00 01/06/2001", "16:00 01/06/2001");
    }

    /** R5 (Q4): lịch đã bắt đầu / đã qua mà ST05 chưa chuyển NO_SHOW không được nhắc. */
    @Test
    void startedOrPastBookedIsNotReminded(CapturedOutput output) {
        long past = booked(counter, DAY, LocalTime.of(8, 30));
        long startingNow = booked(counter, DAY, LocalTime.of(9, 0));
        long yesterday = booked(counter, DAY.minusDays(1), LocalTime.of(15, 0));

        job.run();

        for (long id : List.of(past, startingNow, yesterday)) {
            assertThat(remindedAt(id)).isNull();
            assertThat(outbox(id)).isEmpty();
        }
        assertThat(output).as("cận dưới nằm ở câu quét, không phải lỗi dưới khóa")
                .doesNotContain("APPOINTMENT_REMINDER_FAILED");
        assertThat(service.findDueIds(service.window(clock.instant()), 0, 1000))
                .as("câu quét không trả lịch đã bắt đầu — tồn đọng do ST05 lỗi (D033) không tốn transaction mỗi lượt")
                .doesNotContain(past, startingNow, yesterday);
    }

    /**
     * UPDATE có điều kiện (Q7) tự nó không bao giờ đụng dòng đã rời BOOKED hoặc đã nhắc — lớp chặn thứ hai sau khóa, gọi
     * thẳng repository trong một transaction.
     */
    @Test
    void conditionalUpdateNeverTouchesNonBookedOrAlreadyRemindedRows(@Autowired AppointmentRepository repository) {
        long cancelled = appointment(counter, DAY, LocalTime.of(14, 0), "CANCELLED");
        jdbc.update("UPDATE appointments SET cancel_source = 'CUSTOMER', cancelled_at = now() WHERE id = ?", cancelled);
        long reminded = booked(counter, DAY, LocalTime.of(14, 30));
        jdbc.update("UPDATE appointments SET reminded_at = ? WHERE id = ?", Timestamp.from(T0.minusSeconds(600)),
                reminded);
        long fresh = booked(counter, DAY, LocalTime.of(15, 0));
        String cancelledBefore = jdbc.queryForObject("SELECT to_jsonb(a)::text FROM appointments a WHERE id = ?",
                String.class, cancelled);
        String remindedBefore = jdbc.queryForObject("SELECT to_jsonb(a)::text FROM appointments a WHERE id = ?",
                String.class, reminded);

        List<Integer> counts = tx.execute(s -> List.of(repository.markRemindedIfDue(cancelled, T0),
                repository.markRemindedIfDue(reminded, T0), repository.markRemindedIfDue(fresh, T0)));

        assertThat(counts).containsExactly(0, 0, 1);
        assertThat(jdbc.queryForObject("SELECT to_jsonb(a)::text FROM appointments a WHERE id = ?", String.class,
                cancelled)).isEqualTo(cancelledBefore);
        assertThat(jdbc.queryForObject("SELECT to_jsonb(a)::text FROM appointments a WHERE id = ?", String.class,
                reminded)).isEqualTo(remindedBefore);
    }

    /** R6 + biên: cận trên tính là được, cận dưới loại trừ, chênh 1 µs ở cả hai đầu. */
    @Test
    void windowBoundariesAtOneMicrosecond() {
        long upper = booked(counter, TOMORROW, LocalTime.of(9, 0));
        long beyond = booked(counter, TOMORROW, LocalTime.of(9, 30));

        clock.set(T0.minusNanos(1_000));
        job.run();
        assertThat(remindedAt(upper)).as("còn 24 giờ + 1 µs").isNull();

        clock.set(T0);
        job.run();
        assertThat(remindedAt(upper)).as("đúng 24 giờ trước").isNotNull();
        assertThat(remindedAt(beyond)).isNull();

        long lower = booked(counter, DAY, LocalTime.of(9, 30));
        clock.set(vn(DAY, LocalTime.of(9, 30)));
        job.run();
        assertThat(remindedAt(lower)).as("đúng giờ hẹn: đã bắt đầu").isNull();

        clock.set(vn(DAY, LocalTime.of(9, 30)).minusNanos(1_000));
        job.run();
        assertThat(remindedAt(lower)).as("còn 1 µs").isNotNull();
    }

    /** R7 (Q2): lễ tân đặt (qua API #2) khi đã nằm trong cửa sổ → nhắc ở lượt kế tiếp. */
    @Test
    void bookedInsideTheWindowIsRemindedOnTheNextRun() {
        long pet = data.pet(counter);
        ResponseEntity<Map<String, Object>> response = book(pet, DAY, "15:30");
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        long id = ((Number) data(response).get("appointmentId")).longValue();
        data.appointment(id);

        job.run();

        assertThat(outbox(id)).extracting(r -> r.get("channel"), r -> r.get("recipient_email"))
                .containsExactly(tuple("EMAIL", counterEmail));
    }

    /** R8: đánh dấu thú mất (qua API) hủy lịch trong cùng transaction → không nhắc, chỉ có thông báo hủy. */
    @Test
    void deceasedPetIsNotReminded() {
        long pet = data.pet(counter);
        long id = data.appointment(counter, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(14, 0), "BOOKED",
                receptionist);

        ResponseEntity<Map<String, Object>> response = markDeceased(pet);
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        job.run();

        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(remindedAt(id)).isNull();
        assertThat(outbox(id)).isEmpty();
    }

    /** Q10: dữ liệu lệch — BOOKED mà thú đã mất → không gửi, vẫn đánh dấu, log WARN; không tự hủy lịch. */
    @Test
    void bookedRowOfDeceasedPetIsMarkedButNotSent(CapturedOutput output) {
        long pet = data.pet(counter);
        jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", DAY.minusDays(3), pet);
        long id = data.appointment(counter, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(14, 0), "BOOKED",
                receptionist);

        job.run();

        assertThat(remindedAt(id)).isNotNull();
        assertThat(outbox(id)).isEmpty();
        assertThat(status(id)).isEqualTo("BOOKED");
        assertThat(output).contains("APPOINTMENT_REMINDER_PET_DECEASED appointmentId=" + id);
    }

    /** R10: dịch vụ ngừng sau khi đặt — lịch vẫn hiệu lực (BR-LH-03 chỉ chặn đặt mới) nên vẫn nhắc. */
    @Test
    void inactiveServiceIsStillReminded() {
        long id = booked(counter, DAY, LocalTime.of(14, 0));
        jdbc.update("UPDATE services SET is_active = false WHERE id = ?", medical);

        job.run();

        assertThat(outbox(id)).hasSize(1);
    }

    /** R11 (A3): cờ chờ liên kết không chặn nhắc lịch đã có. */
    @Test
    void linkDecisionPendingStillReminded() {
        jdbc.update("UPDATE customers SET link_decision_pending = true WHERE id = ?", online);
        long id = booked(online, DAY, LocalTime.of(14, 0));

        job.run();

        assertThat(outbox(id)).hasSize(2);
    }

    /** R12 (A2): tài khoản bị khóa vẫn nhận nhắc (BR-QT-11 giữ nguyên lịch). */
    @Test
    void lockedAccountStillReminded() {
        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'IT' WHERE id = ?", account);
        long id = booked(online, DAY, LocalTime.of(14, 0));

        job.run();

        assertThat(outbox(id)).extracting(r -> r.get("channel")).containsExactlyInAnyOrder("IN_APP", "EMAIL");
    }

    // ================================================================ C — người nhận (BR-TB-02)

    @Test
    void counterProfileWithEmailGetsEmailOnly() {
        long id = booked(counter, DAY, LocalTime.of(14, 0));

        job.run();

        assertThat(outbox(id)).extracting(r -> r.get("channel"), r -> r.get("recipient_account_id"),
                r -> r.get("recipient_email")).containsExactly(tuple("EMAIL", null, counterEmail));
    }

    /** Q3: không kênh → không outbox, không Care Task, vẫn đánh dấu để không xét lại, log INFO. */
    @Test
    void noChannelIsMarkedWithoutOutboxOrCareTask(CapturedOutput output) {
        long id = booked(silent, DAY, LocalTime.of(14, 0));

        job.run();

        assertThat(remindedAt(id)).isNotNull();
        assertThat(outbox(id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM care_tasks WHERE customer_id = ?", Integer.class, silent))
                .isZero();
        assertThat(output).contains("APPOINTMENT_REMINDER_NO_CHANNEL appointmentId=" + id + " customerId=" + silent);
    }

    @Test
    void pendingAccountIsTreatedAsNoAccount() {
        long pending = data.customerAccount("remind-pending-" + UUID.randomUUID() + "@example.com", "PENDING");
        long profile = data.onlineCustomer(pending, "Chưa Xác Thực");
        long id = booked(profile, DAY, LocalTime.of(14, 0));

        job.run();

        assertThat(remindedAt(id)).isNotNull();
        assertThat(outbox(id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE recipient_account_id = ?",
                Integer.class, pending)).isZero();
    }

    /** Payload ⊆ {@code allowed_vars}, ⊇ {@code required_vars} của dòng V18; ST20 gửi email và giao IN_APP được. */
    @Test
    void payloadMatchesTemplateRowsAndSt20DeliversIt() throws Exception {
        long a = booked(online, DAY, LocalTime.of(14, 0));
        long c = booked(counter, DAY, LocalTime.of(14, 30));
        String code = code(a);

        job.run();

        assertThat(jdbc.queryForList("""
                SELECT o.id FROM notification_outbox o JOIN notification_templates t ON t.code = o.template_code
                WHERE o.%s AND o.payload->>'ma_lich_hen' IN (?, ?)
                  AND (NOT (t.allowed_vars @> (SELECT coalesce(jsonb_agg(k), '[]'::jsonb)
                                               FROM jsonb_object_keys(o.payload) k WHERE k <> 'link_url'))
                       OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(t.required_vars) v
                                  WHERE coalesce(o.payload->>v, '') = ''))""".formatted(REMINDER_TEMPLATES),
                Long.class, code, code(c))).as("payload lệch biến của mẫu").isEmpty();
        assertThat(outbox(a)).allSatisfy(r -> assertThat(r.get("payload").toString())
                .contains("\"chi_nhanh\"", "\"dia_chi_chi_nhanh\": \"Địa chỉ\"", "\"so_dien_thoai_chi_nhanh\": \"0280000000\"",
                        "\"gio_hen\": \"14:00 01/06/2001\"", "\"ma_lich_hen\": \"" + code + "\""));

        inAppJob.run();
        assertThat(jdbc.queryForList("SELECT type, title, link_url FROM notifications WHERE account_id = ?", account))
                .singleElement().satisfies(n -> assertThat(n).containsEntry("type", "APPOINTMENT_REMINDER")
                        .containsEntry("title", "Nhắc lịch hẹn của Milu").containsEntry("link_url", "/appointments"));

        Map<String, MimeMessage> mails = sendEmails(accountEmail, counterEmail);
        assertThat(mails.get(accountEmail).getSubject()).isEqualTo("Nhắc lịch hẹn của Milu lúc 14:00 01/06/2001");
        assertThat(mails.get(counterEmail).getContent().toString()).contains("Xin chào Lê Văn Quầy")
                .contains(code(c)).contains("0280000000").doesNotContain("{");
        assertThat(jdbc.queryForList("SELECT status FROM notification_outbox WHERE " + REMINDER_TEMPLATES
                + " AND payload->>'ma_lich_hen' IN (?, ?)", String.class, code, code(c))).containsOnly("SENT");
    }

    // ================================================================ T — lúc nào nhắc, [CFG]

    /** T1 (A1): đổi H qua [CFG] có hiệu lực từ lượt sau. */
    @Test
    void configChangeAppliesFromNextRun() {
        long id = booked(counter, TOMORROW, LocalTime.of(15, 0)); // còn 30 giờ
        String key = "appointment.reminder_hours_before";
        String original = jdbc.queryForObject("SELECT value FROM system_configs WHERE key = ?", String.class, key);

        job.run();
        assertThat(remindedAt(id)).isNull();
        try {
            jdbc.update("UPDATE system_configs SET value = '48' WHERE key = ?", key);
            configs.reload();

            job.run();
            assertThat(remindedAt(id)).isNotNull();
        } finally {
            jdbc.update("UPDATE system_configs SET value = ? WHERE key = ?", original, key);
            configs.reload();
        }
    }

    // ================================================================ nguyên tử, khóa

    /** INSERT outbox lỗi → rollback cả {@code reminded_at}; lượt sau (sau khi hết lỗi) nhắc lại. */
    @Test
    void outboxFailureRollsBackTheMarkAndTheNextRunRetries(CapturedOutput output) {
        long id = booked(counter, DAY, LocalTime.of(14, 0));
        String code = code(id);
        jdbc.execute("""
                CREATE FUNCTION reminder_it_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'reminder_it: outbox bị chặn'; END $$""");
        jdbc.execute("CREATE TRIGGER reminder_it_fail BEFORE INSERT ON notification_outbox FOR EACH ROW WHEN "
                + "(NEW.payload->>'ma_lich_hen' = '" + code + "') EXECUTE FUNCTION reminder_it_fail()");

        job.run();

        assertThat(remindedAt(id)).isNull();
        assertThat(outbox(id)).isEmpty();
        assertThat(output).contains("APPOINTMENT_REMINDER_FAILED appointmentId=" + id);

        jdbc.execute("DROP TRIGGER reminder_it_fail ON notification_outbox");
        job.run();
        assertThat(remindedAt(id)).isNotNull();
        assertThat(outbox(id)).hasSize(1);
    }

    /** Dòng đang bị luồng khác giữ → bỏ qua ngay (SKIP LOCKED), không chờ. */
    @Test
    void rowHeldByAnotherTransactionIsSkippedWithoutWaiting() throws Exception {
        long id = booked(counter, DAY, LocalTime.of(14, 0));
        ReminderWindow window = service.window(clock.instant());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT id FROM appointments WHERE id = ? FOR NO KEY UPDATE", Long.class, id);
            held.countDown();
            await(release);
        }));
        try {
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            ReminderResult result = CompletableFuture.supplyAsync(() -> service.remind(id, window))
                    .get(5, TimeUnit.SECONDS);
            assertThat(result).isEqualTo(ReminderResult.SKIPPED);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
        assertThat(remindedAt(id)).isNull();
        job.run();
        assertThat(remindedAt(id)).isNotNull();
    }

    // ================================================================ ma trận đồng thời C1–C7

    /** C1a: hủy (lễ tân, còn < 12 giờ → hủy muộn) giữ dòng trước → ST03 bỏ qua ngay; không bao giờ nhắc. */
    @Test
    void cancelHoldingTheRowWinsAndIsNeverReminded() throws Exception {
        long id = booked(counter, DAY, LocalTime.of(11, 0));

        ResponseEntity<Map<String, Object>> response = whileOtherFlowHoldsRow(id, () -> cancel(id));

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(row(id)).containsEntry("status", "CANCELLED").containsEntry("late_cancel", true)
                .containsEntry("reminded_at", null);
        job.run();
        assertThat(outbox(id)).isEmpty();
    }

    /** C1b: ST03 giữ dòng trước → hủy chờ rồi chạy trên dòng mới: CANCELLED, hủy muộn, giữ {@code reminded_at}; 1 nhắc. */
    @Test
    void reminderHoldingTheRowThenCancelKeepsTheMark() throws Exception {
        long id = booked(counter, DAY, LocalTime.of(11, 0));

        Results results = whileReminderHoldsRow(id, () -> cancel(id));

        assertThat(results.reminder()).isEqualTo(ReminderResult.REMINDED);
        assertThat(results.other().getStatusCode()).as("%s", results.other().getBody()).isEqualTo(HttpStatus.OK);
        assertThat(row(id)).containsEntry("status", "CANCELLED").containsEntry("late_cancel", true);
        assertThat(remindedAt(id)).isEqualTo(T0);
        assertThat(outbox(id)).hasSize(1);
    }

    /** C2a: đổi khung giữ dòng trước → ST03 bỏ qua; lượt sau nhắc khung mới. */
    @Test
    void rescheduleHoldingTheRowWinsAndTheNewSlotIsReminded() throws Exception {
        long id = booked(counter, DAY, LocalTime.of(11, 0));

        ResponseEntity<Map<String, Object>> response = whileOtherFlowHoldsRow(id, () -> reschedule(id, DAY, "16:00"));

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        job.run();
        assertThat(outbox(id)).extracting(r -> r.get("gio_hen")).containsExactly("16:00 01/06/2001");
    }

    /**
     * C2b: ST03 giữ dòng trước → đổi khung chờ, đọc dòng đã đánh dấu, xóa mốc; khung mới không bị ghi đè (ST03 chỉ ghi hai
     * cột); lượt sau nhắc lại khung mới → 2 nhắc với giờ khác nhau.
     */
    @Test
    void reminderHoldingTheRowThenRescheduleKeepsNewSlotAndRemindsAgain() throws Exception {
        long id = booked(counter, DAY, LocalTime.of(11, 0));

        Results results = whileReminderHoldsRow(id, () -> reschedule(id, DAY, "16:00"));

        assertThat(results.reminder()).isEqualTo(ReminderResult.REMINDED);
        assertThat(results.other().getStatusCode()).as("%s", results.other().getBody()).isEqualTo(HttpStatus.OK);
        assertThat(row(id)).containsEntry("slot_start", java.sql.Time.valueOf(LocalTime.of(16, 0)))
                .containsEntry("reschedule_count", 1).containsEntry("reminded_at", null);
        job.run();
        assertThat(outbox(id)).extracting(r -> r.get("gio_hen"))
                .containsExactlyInAnyOrder("11:00 01/06/2001", "16:00 01/06/2001");
    }

    /** C3a: check-in giữ dòng trước → ST03 bỏ qua; không bao giờ nhắc. */
    @Test
    void checkInHoldingTheRowWinsAndIsNeverReminded() throws Exception {
        long id = booked(counter, DAY, LocalTime.of(9, 30));

        whileOtherFlowHoldsRow(id, () -> {
            tx.executeWithoutResult(s -> appointmentApi.checkIn(id, branch, clock.instant()));
            return null;
        });

        assertThat(status(id)).isEqualTo("CHECKED_IN");
        job.run();
        assertThat(remindedAt(id)).isNull();
        assertThat(outbox(id)).isEmpty();
    }

    /** C3b: ST03 giữ dòng trước → check-in chờ rồi thành công; nhắc đã gửi (khách đã ở quầy — chấp nhận). */
    @Test
    void reminderHoldingTheRowThenCheckInSucceeds() throws Exception {
        long id = booked(counter, DAY, LocalTime.of(9, 30));

        Results results = whileReminderHoldsRow(id, () -> {
            tx.executeWithoutResult(s -> appointmentApi.checkIn(id, branch, clock.instant()));
            return null;
        });

        assertThat(results.reminder()).isEqualTo(ReminderResult.REMINDED);
        assertThat(status(id)).isEqualTo("CHECKED_IN");
        assertThat(outbox(id)).hasSize(1);
    }

    /** C4a: đánh dấu thú mất (listener hủy do phòng khám) giữ dòng trước → ST03 bỏ qua; chỉ có thông báo hủy. */
    @Test
    void deceasedHoldingTheRowWinsAndIsNeverReminded() throws Exception {
        long pet = data.pet(counter);
        long id = data.appointment(counter, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(14, 0), "BOOKED",
                receptionist);

        ResponseEntity<Map<String, Object>> response = whileOtherFlowHoldsRow(id, () -> markDeceased(pet));

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(row(id)).containsEntry("status", "CANCELLED").containsEntry("cancel_source", "CLINIC");
        job.run();
        assertThat(outbox(id)).isEmpty();
    }

    /** C4b: ST03 giữ dòng trước → listener của thú mất chờ rồi hủy; 1 nhắc + 1 thông báo hủy, không deadlock. */
    @Test
    void reminderHoldingTheRowThenDeceasedCancelsByClinic() throws Exception {
        long pet = data.pet(counter);
        long id = data.appointment(counter, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(14, 0), "BOOKED",
                receptionist);

        Results results = whileReminderHoldsRow(id, () -> markDeceased(pet));

        assertThat(results.reminder()).isEqualTo(ReminderResult.REMINDED);
        assertThat(results.other().getStatusCode()).as("%s", results.other().getBody()).isEqualTo(HttpStatus.OK);
        assertThat(row(id)).containsEntry("status", "CANCELLED").containsEntry("cancel_source", "CLINIC");
        assertThat(outbox(id)).hasSize(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox WHERE template_code = 'APPOINTMENT_CANCELLED_BY_CLINIC'
                  AND recipient_email = ?""", Integer.class, counterEmail)).isEqualTo(1);
    }

    /** C4b với ngày nghỉ: thêm ngày nghỉ có hủy hàng loạt chờ ST03 nhả dòng rồi hủy; 201, không deadlock. */
    @Test
    void reminderHoldingTheRowThenHolidayCancelsByClinic() throws Exception {
        long id = booked(counter, TOMORROW, LocalTime.of(8, 30));
        Map<String, Object> body = new HashMap<>();
        body.put("holidayDate", TOMORROW.toString());
        body.put("reason", "IT nghỉ");
        body.put("cancelAffected", true);

        Results results = whileReminderHoldsRow(id,
                () -> call(HttpMethod.POST, "/api/branches/" + branch + "/holidays", managerToken, body));

        assertThat(results.reminder()).isEqualTo(ReminderResult.REMINDED);
        assertThat(results.other().getStatusCode()).as("%s", results.other().getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(row(id)).containsEntry("status", "CANCELLED").containsEntry("cancel_source", "CLINIC");
    }

    /** C5: ST03 và ST05 chạy song song trên cùng dữ liệu — mỗi dòng bị tối đa một job đụng. */
    @Test
    void reminderAndNoShowJobsTouchDisjointRows() throws Exception {
        long overdue = booked(counter, DAY, LocalTime.of(8, 0));
        long due = booked(counter, DAY, LocalTime.of(10, 0));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> a = pool.submit(() -> {
                await(start);
                job.run();
            });
            Future<?> b = pool.submit(() -> {
                await(start);
                noShowJob.run();
            });
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(row(overdue)).containsEntry("status", "NO_SHOW").containsEntry("reminded_at", null);
        assertThat(row(due)).containsEntry("status", "BOOKED");
        assertThat(remindedAt(due)).isNotNull();
        assertThat(outbox(overdue)).isEmpty();
    }

    /** C6: hai lượt ST03 song song → mỗi lịch đúng một nhắc. */
    @Test
    void twoConcurrentRunsRemindEachAppointmentExactlyOnce(CapturedOutput output) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            ids.add(booked(counter, i < 8 ? DAY : TOMORROW, LocalTime.of(i < 8 ? 10 + i % 7 : 8, i % 2 * 30)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    await(start);
                    job.run();
                }));
            }
            start.countDown();
            for (Future<?> run : runs) {
                run.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        for (long id : ids) {
            assertThat(outbox(id)).as("lịch %d", id).hasSize(1);
        }
        assertThat(output).as("lượt thứ hai bỏ qua ở câu khóa, không rơi vào lỗi của UPDATE có điều kiện")
                .doesNotContain("APPOINTMENT_REMINDER_FAILED");
    }

    // ================================================================ quét, index, phạm vi

    /** Keyset theo khung giờ rồi id: mỗi lịch đúng một lần qua các lô, kể cả khi thứ tự id ngược thứ tự khung giờ. */
    @Test
    void batchesWalkInSlotOrderAndVisitEachAppointmentOnce() {
        long late = booked(counter, TOMORROW, LocalTime.of(8, 0));
        long sameSlotA = booked(counter, DAY, LocalTime.of(15, 0));
        long sameSlotB = booked(counter, DAY, LocalTime.of(15, 0));
        long early = booked(counter, DAY, LocalTime.of(10, 30));
        ReminderWindow window = service.window(clock.instant());
        List<Long> mine = List.of(late, sameSlotA, sameSlotB, early);

        List<Long> walked = new ArrayList<>();
        long after = 0;
        for (List<Long> batch = service.findDueIds(window, after, 1); !batch.isEmpty();
                batch = service.findDueIds(window, after, 1)) {
            walked.addAll(batch);
            after = batch.get(0);
        }

        assertThat(walked).doesNotHaveDuplicates();
        assertThat(walked.stream().filter(mine::contains).toList()).containsExactly(early, sameSlotA, sameSlotB, late);
    }

    /**
     * Dữ liệu gần thật (20 000 dòng chèn trong transaction rollback + {@code ANALYZE}, khuôn {@code NoShowIT}): 50 ngày,
     * quá khứ ở trạng thái cuối, tương lai {@code BOOKED}; cửa sổ 24 giờ chỉ chứa một ngày. Câu quét đi theo
     * {@code ix_appointments_status_slot}, câu khóa theo khóa chính.
     */
    @Test
    void reminderQueriesUseTheirIndexesOnRealisticData() {
        Map<String, String> plans = tx.execute(s -> {
            s.setRollbackOnly();
            jdbc.update("""
                    INSERT INTO customers (full_name, phone, created_channel)
                    SELECT 'REMIND-EXPLAIN ' || g, '0900000000', 'COUNTER' FROM generate_series(1, 2000) g""");
            jdbc.update("""
                    INSERT INTO pets (customer_id, name, species, sex)
                    SELECT id, 'REMIND-EXPLAIN', 'DOG', 'MALE' FROM customers WHERE full_name LIKE 'REMIND-EXPLAIN %'""");
            jdbc.update("""
                    INSERT INTO appointments (code, customer_id, pet_id, branch_id, service_id, service_group, slot_date,
                            slot_start, status, channel, booked_by, reminded_at)
                    SELECT 'LH-RMX-' || g, p.customer_id, p.id, ?, ?, 'MEDICAL', DATE '2026-01-01' + (g / 400),
                           TIME '08:00' + (g % 8) * INTERVAL '30 minutes',
                           CASE WHEN g / 400 >= 25 THEN 'BOOKED'
                                ELSE (ARRAY['COMPLETED', 'CANCELLED', 'NO_SHOW'])[1 + g % 3] END, 'COUNTER', ?,
                           CASE WHEN g / 400 < 25 THEN TIMESTAMPTZ '2026-01-01' END
                    FROM generate_series(0, 19999) g
                    JOIN (SELECT id, customer_id, row_number() OVER (ORDER BY id) - 1 AS n FROM pets
                          WHERE name = 'REMIND-EXPLAIN') p ON p.n = g % 2000""", branch, medical, receptionist);
            jdbc.execute("ANALYZE appointments");
            // Cửa sổ (2026-01-26 07:00, 2026-01-27 07:00]: một ngày = 400 lịch BOOKED chưa nhắc.
            LocalDate fromDate = LocalDate.of(2026, 1, 26);
            LocalDate toDate = LocalDate.of(2026, 1, 27);
            String time = "07:00";
            long probe = jdbc.queryForObject("SELECT min(id) FROM appointments WHERE code LIKE 'LH-RMX-%'", Long.class);
            long middle = jdbc.queryForObject("""
                    SELECT id FROM appointments WHERE code LIKE 'LH-RMX-%' AND slot_date = DATE '2026-01-26'
                    ORDER BY slot_date, slot_start, id OFFSET 100 LIMIT 1""", Long.class);
            Map<String, String> result = new HashMap<>();
            result.put("first", plan("findDueForReminderIds",
                    new Class<?>[] {LocalDate.class, String.class, LocalDate.class, String.class, int.class},
                    fromDate, time, toDate, time, 100));
            result.put("next", plan("findDueForReminderIdsAfter",
                    new Class<?>[] {LocalDate.class, String.class, LocalDate.class, String.class, long.class, int.class},
                    fromDate, time, toDate, time, middle, 100));
            result.put("lock", plan("lockDueForReminder",
                    new Class<?>[] {long.class, LocalDate.class, String.class, LocalDate.class, String.class},
                    probe, fromDate, time, toDate, time));
            result.put("rows", String.valueOf(jdbc.queryForObject("""
                    SELECT count(*) FROM appointments WHERE code LIKE 'LH-RMX-%' AND status = 'BOOKED'
                      AND reminded_at IS NULL
                      AND (slot_date, slot_start) > (DATE '2026-01-26', TIME '07:00')
                      AND (slot_date, slot_start) <= (DATE '2026-01-27', TIME '07:00')""", Integer.class)));
            return result;
        });

        assertThat(plans.get("rows")).as("dữ liệu đúng hình dạng: một ngày trong cửa sổ").isEqualTo("400");
        assertThat(plans.get("first")).doesNotContain("Seq Scan").doesNotContain("appointments_pkey")
                .contains("Index Scan using ix_appointments_status_slot on appointments");
        assertThat(plans.get("next")).doesNotContain("Seq Scan")
                .contains("Index Scan using ix_appointments_status_slot on appointments a")
                .contains("appointments_pkey on appointments p");
        assertThat(plans.get("lock")).doesNotContain("Seq Scan").contains("appointments_pkey");
    }

    @Test
    void onlyTheJobDependsOnTheService() {
        assertThat(beans.getDependentBeans("appointmentReminderService"))
                .filteredOn(name -> !name.contains("AppointmentReminderIT")).containsExactly("appointmentReminderJob");
    }

    // ================================================================ helpers — dựng đồng thời

    private record Results(ReminderResult reminder, ResponseEntity<Map<String, Object>> other) {}

    /**
     * Luồng kia khóa dòng trước: trigger tạm làm UPDATE của nó (đã khóa dòng) chờ advisory {@value #PAUSE_NAMESPACE} do
     * test giữ; trong lúc đó ST03 phải bỏ qua dòng ngay (≤ 5 giây — bỏ {@code SKIP LOCKED} thì đỏ). Nhả khóa → luồng kia
     * commit. Trả kết quả của luồng kia.
     */
    private ResponseEntity<Map<String, Object>> whileOtherFlowHoldsRow(long appointmentId,
            Callable<ResponseEntity<Map<String, Object>>> otherFlow) throws Exception {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION reminder_it_pause() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM pg_advisory_xact_lock(%d, 1); RETURN NEW; END $$""".formatted(PAUSE_NAMESPACE));
        jdbc.execute("CREATE TRIGGER reminder_it_pause BEFORE UPDATE ON appointments FOR EACH ROW WHEN (NEW.id = "
                + appointmentId + ") EXECUTE FUNCTION reminder_it_pause()");
        ReminderWindow window = service.window(clock.instant());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(?, 1)::text", String.class, PAUSE_NAMESPACE);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ResponseEntity<Map<String, Object>>> other = pool.submit(otherFlow);
            awaitWaiting(WAITING_IN_PAUSED_UPDATE);

            ReminderResult result = CompletableFuture.supplyAsync(() -> service.remind(appointmentId, window))
                    .get(5, TimeUnit.SECONDS);
            assertThat(result).as("dòng đang bị luồng kia giữ").isEqualTo(ReminderResult.SKIPPED);
            assertThat(other.isDone()).isFalse();

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            return other.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            jdbc.execute("DROP TRIGGER IF EXISTS reminder_it_pause ON appointments");
        }
    }

    /**
     * ST03 khóa dòng trước: test giữ {@code FOR UPDATE} hai dòng mẫu nhắc, nên ST03 (đã khóa dòng, đã đánh dấu) đứng chờ ở
     * INSERT outbox; luồng kia gửi sau và phải chờ khóa dòng lịch hẹn. Nhả mẫu → ST03 commit → luồng kia chạy tiếp.
     */
    private Results whileReminderHoldsRow(long appointmentId, Callable<ResponseEntity<Map<String, Object>>> otherFlow)
            throws Exception {
        ReminderWindow window = service.window(clock.instant());
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForList("SELECT code FROM notification_templates WHERE code IN ('APPOINTMENT_REMINDER', "
                        + "'APPOINTMENT_REMINDER_APP') FOR UPDATE");
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ReminderResult> reminder = pool.submit(() -> service.remind(appointmentId, window));
            awaitWaiting(WAITING_ON_OUTBOX);
            Future<ResponseEntity<Map<String, Object>>> other = pool.submit(otherFlow);
            awaitWaiting(WAITING_ON_ROW);
            assertThat(reminder.isDone()).isFalse();
            assertThat(other.isDone()).isFalse();

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            ReminderResult result = reminder.get(30, TimeUnit.SECONDS);
            return new Results(result, other.get(30, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** Chờ tới khi có ít nhất một câu khớp {@code queryLike} đứng chờ khóa. */
    private void awaitWaiting(String queryLike) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE ?""",
                    Integer.class, queryLike);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("không thấy câu chờ khóa khớp " + queryLike);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ================================================================ helpers — dữ liệu

    private static Instant vn(LocalDate date, LocalTime time) {
        return ZonedDateTime.of(LocalDateTime.of(date, time), TimeConfig.BUSINESS_ZONE).toInstant();
    }

    private long booked(long customer, LocalDate date, LocalTime start) {
        return appointment(customer, date, start, "BOOKED");
    }

    private long appointment(long customer, LocalDate date, LocalTime start, String status) {
        return data.appointment(customer, data.pet(customer), branch, medical, "MEDICAL", date, start, status,
                receptionist);
    }

    private String code(long id) {
        return jdbc.queryForObject("SELECT code FROM appointments WHERE id = ?", String.class, id);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, id);
    }

    private Instant remindedAt(long id) {
        Timestamp at = jdbc.queryForObject("SELECT reminded_at FROM appointments WHERE id = ?", Timestamp.class, id);
        return at == null ? null : at.toInstant().truncatedTo(ChronoUnit.MICROS);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("""
                SELECT status, late_cancel, cancel_source, slot_start, reschedule_count, reminded_at
                FROM appointments WHERE id = ?""", id);
    }

    /** Mọi cột trừ hai cột ST03 được ghi. */
    private String rowWithoutReminder(long id) {
        return jdbc.queryForObject("SELECT (to_jsonb(a) - 'reminded_at' - 'updated_at')::text FROM appointments a "
                + "WHERE id = ?", String.class, id);
    }

    /** Dòng outbox của mẫu nhắc mang mã lịch hẹn {@code id}. */
    private List<Map<String, Object>> outbox(long id) {
        return jdbc.queryForList("""
                SELECT channel, recipient_account_id, recipient_email, payload->>'link_url' AS link_url,
                       payload::text AS payload,
                       payload->>'gio_hen' AS gio_hen
                FROM notification_outbox WHERE %s AND payload->>'ma_lich_hen' = ?""".formatted(REMINDER_TEMPLATES),
                code(id));
    }

    private Map<String, MimeMessage> sendEmails(String... recipients) throws Exception {
        Set<String> wanted = Set.of(recipients);
        Map<String, MimeMessage> found = new HashMap<>();
        for (int round = 0; round < 20 && found.size() < wanted.size(); round++) {
            emailJob.run();
            for (MimeMessage message : SMTP.getReceivedMessages()) {
                for (var address : message.getRecipients(Message.RecipientType.TO)) {
                    if (wanted.contains(address.toString())) {
                        found.put(address.toString(), message);
                    }
                }
            }
        }
        assertThat(found.keySet()).containsExactlyInAnyOrder(recipients);
        return found;
    }

    private String plan(String method, Class<?>[] types, Object... args) {
        try {
            String sql = AppointmentRepository.class.getMethod(method, types).getAnnotation(Query.class).value()
                    .replaceAll(":[a-zA-Z]+", "?");
            return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class, args));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    // ================================================================ helpers — HTTP

    private String token(long accountId) {
        return tx.execute(s -> sessions.open(accountId, "203.0.113.9", "AppointmentReminderIT")).accessToken();
    }

    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return http.exchange(URI.create(http.getRootUri() + path), method, new HttpEntity<>(body, headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> book(long petId, LocalDate date, String start) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("petId", petId);
        body.put("branchId", branch);
        body.put("serviceId", medical);
        body.put("slotDate", date.toString());
        body.put("slotStart", start);
        return call(HttpMethod.POST, "/api/appointments", receptionistToken, body);
    }

    private ResponseEntity<Map<String, Object>> reschedule(long id, LocalDate date, String start) {
        return call(HttpMethod.POST, "/api/appointments/" + id + "/reschedule", receptionistToken,
                Map.of("slotDate", date.toString(), "slotStart", start));
    }

    private ResponseEntity<Map<String, Object>> cancel(long id) {
        return call(HttpMethod.POST, "/api/appointments/" + id + "/cancel", receptionistToken, Map.of());
    }

    private ResponseEntity<Map<String, Object>> markDeceased(long pet) {
        return call(HttpMethod.POST, "/api/pets/" + pet + "/mark-deceased", receptionistToken,
                Map.of("deceasedOn", DAY.toString(), "confirmed", true));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }
}
