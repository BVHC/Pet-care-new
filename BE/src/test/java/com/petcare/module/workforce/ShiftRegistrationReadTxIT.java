package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.support.MutableClock;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Transaction của {@code GET /me/shift-registrations/{weekStart}} (workforce-v1 #17; docs/adr/0149): một transaction
 * chỉ đọc REPEATABLE_READ gồm cả bước ghép của {@code toResponses}; ảnh chụp nhất quán dù có commit xen giữa; kết nối
 * trả về pool đúng trạng thái; lỗi DB và lỗi nghiệp vụ không ghi gì và không giữ kết nối. Spy repository của identity
 * (không spy bean {@code @Transactional}, bẫy Mockito) nên cần context riêng, tách khỏi {@code ShiftRegistrationReadIT}.
 * Era <b>năm 2085</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRegistrationReadTxIT.TestBeans.class})
class ShiftRegistrationReadTxIT extends ShiftRegistrationItSupport {

    private static final Instant NOW = Instant.parse("2085-03-14T01:00:00Z");
    private static final LocalDate WEEK = mondayOnOrAfter(LocalDate.of(2085, 3, 14));
    private static final String TX_STATE = """
            SELECT current_setting('transaction_isolation') || '|' || current_setting('transaction_read_only')
            """;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @MockitoSpyBean
    private StaffProfileRepository staffProfiles;
    @Autowired
    private DataSource dataSource;

    private long branch;
    private long staff;
    private long morning;
    private long afternoon;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        staff = data.staff("RECEPTIONIST", branch);
        morning = definition(branch, "Ca sáng", "08:00", "12:00", true);
        afternoon = definition(branch, "Ca chiều", "13:00", "17:00", true);
    }

    @AfterEach
    void tearDown() {
        org.mockito.Mockito.reset(staffProfiles);
        finish();
    }

    @Test
    void runsInOneReadOnlyRepeatableReadTransactionIncludingTheAssemblySteps() {
        data.assignment(branch, staff, morning, WEEK, "SCHEDULED", "SELF", null);
        AtomicReference<String> state = new AtomicReference<>();
        doAnswer(call -> {
            // Chạy trên luồng request, giữa lúc ghép tên: JdbcTemplate tham gia đúng transaction đang mở.
            state.set(jdbc.queryForObject(TX_STATE, String.class));
            return realCall(call);
        }).when(staffProfiles).findStaffRows(anyCollection());

        ResponseEntity<Map<String, Object>> response = registration(token(staff), WEEK);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(state.get()).as("transaction của bước ghép").isEqualTo("repeatable read|on");
    }

    @Test
    void aWriteCommittedWhileTheWeekIsBeingAssembledIsNotSeenByThatCallButByTheNext() {
        long first = data.assignment(branch, staff, morning, WEEK, "SCHEDULED", "SELF", null);
        long second = data.assignment(branch, staff, afternoon, WEEK, "SCHEDULED", "MANAGER", null);
        String originalName = nameOf(staff);
        AtomicBoolean fired = new AtomicBoolean();
        doAnswer(call -> {
            if (fired.compareAndSet(false, true)) {
                // Luồng khác (autocommit), không phải luồng request: sau khi câu danh sách đã chạy, trước khi ghép tên
                // và yêu cầu chờ. Dưới READ COMMITTED các câu sau sẽ thấy thay đổi này.
                CompletableFuture.runAsync(() -> {
                    jdbc.update("UPDATE staff_profiles SET full_name = ? WHERE account_id = ?", "Tên đã đổi", staff);
                    data.leaveRequest(first, staff, "PENDING", null);
                }).get(10, TimeUnit.SECONDS);
            }
            return realCall(call);
        }).when(staffProfiles).findStaffRows(anyCollection());

        ResponseEntity<Map<String, Object>> during = registration(token(staff), WEEK);

        assertThat(fired).as("spy phải được gọi đúng một lần giữa lúc ghép").isTrue();
        assertThat(during.getStatusCode().value()).isEqualTo(200);
        assertThat(assignmentsOf(during)).extracting(row -> idOf(row, "shiftAssignmentId")).containsExactly(first, second);
        assertThat(assignmentsOf(during)).extracting(row -> row.get("staffName")).containsOnly(originalName);
        assertThat(assignmentsOf(during)).extracting(row -> row.get("pendingRequestId")).containsOnlyNulls();

        ResponseEntity<Map<String, Object>> after = registration(token(staff), WEEK);

        assertThat(assignmentsOf(after)).extracting(row -> row.get("staffName")).containsOnly("Tên đã đổi");
        assertThat(assignmentsOf(after).get(0).get("pendingRequestId")).isNotNull();
        assertThat(assignmentsOf(after).get(1)).containsEntry("pendingRequestId", null);
    }

    @Test
    void connectionsReturnToThePoolAsReadCommittedReadWrite() throws Exception {
        data.assignment(branch, staff, morning, WEEK, "SCHEDULED", "SELF", null);
        String token = token(staff);
        for (int i = 0; i < 5; i++) {
            assertThat(registration(token, WEEK).getStatusCode().value()).isEqualTo(200);
            assertThat(registration(token, WEEK.plusDays(1)).getStatusCode().value()).isEqualTo(400);
        }

        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        List<Connection> borrowed = new ArrayList<>();
        try {
            for (int i = 0; i < hikari.getMaximumPoolSize(); i++) {
                borrowed.add(hikari.getConnection());
            }
            for (Connection connection : borrowed) {
                assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
                assertThat(connection.isReadOnly()).isFalse();
                try (Statement statement = connection.createStatement();
                        ResultSet rs = statement.executeQuery(
                                "SELECT current_setting('transaction_isolation'), "
                                        + "current_setting('transaction_read_only')")) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("read committed");
                    assertThat(rs.getString(2)).isEqualTo("off");
                }
            }
        } finally {
            for (Connection connection : borrowed) {
                connection.close();
            }
        }
    }

    @Test
    void aFailedQueryIs500ReleasesTheConnectionAndWritesNothing() throws Exception {
        data.assignment(branch, staff, morning, WEEK, "SCHEDULED", "SELF", null);
        String token = token(staff);
        String before = footprint(staff);
        AtomicBoolean failed = new AtomicBoolean();
        doAnswer(call -> {
            if (failed.compareAndSet(false, true)) {
                throw new DataAccessResourceFailureException("IT: DB tạm mất kết nối");
            }
            return realCall(call);
        }).when(staffProfiles).findStaffRows(anyCollection());

        ResponseEntity<Map<String, Object>> response = registration(token, WEEK);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(errorCode(response)).isEqualTo("INTERNAL_ERROR");
        assertThat(dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(footprint(staff)).isEqualTo(before);
        assertThat(registration(token, WEEK).getStatusCode().value()).as("lần sau bình thường").isEqualTo(200);
    }

    @Test
    void aBusinessRuleFailureWritesNothingAndHoldsNoConnection() throws Exception {
        data.assignment(branch, staff, morning, WEEK, "SCHEDULED", "SELF", null);
        String token = token(staff);
        String before = footprint(staff);

        assertBusinessRule(registration(token, WEEK.plusDays(2)), "BR-NS-11");
        assertBusinessRule(registration(token, firstMondayOf(2101)), "BR-NS-11");

        assertThat(dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(footprint(staff)).isEqualTo(before);
    }

    /** Gọi bản thật của repository (spy mặc định) thay vì stub; cùng cách dùng ở các IT khác của dự án. */
    private Object realCall(InvocationOnMock call) throws Throwable {
        return mockingDetails(staffProfiles).getMockCreationSettings().getDefaultAnswer().answer(call);
    }

    private String nameOf(long accountId) {
        return jdbc.queryForObject("SELECT full_name FROM staff_profiles WHERE account_id = ?", String.class, accountId);
    }
}
