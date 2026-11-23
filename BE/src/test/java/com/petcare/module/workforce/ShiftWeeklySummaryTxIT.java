package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
 * Transaction của {@code GET /shift-weekly-summaries} (workforce-v1 #16; docs/adr/0149 mục "Thống kê tuần"): một
 * transaction chỉ đọc REPEATABLE_READ bao cả ba lần lấy nhân viên, câu đếm và định mức; ảnh chụp nhất quán dù có commit
 * xen giữa (đổi tên, thêm phân ca) nhưng lần gọi sau thấy; kết nối trả về pool đúng trạng thái; lỗi DB và lỗi nghiệp vụ
 * không ghi gì và không giữ kết nối. Spy repository của identity (không spy bean {@code @Transactional}, bẫy Mockito)
 * nên cần context riêng, tách khỏi {@code ShiftWeeklySummaryIT}. Era <b>năm 2092</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftWeeklySummaryTxIT.TestBeans.class})
class ShiftWeeklySummaryTxIT extends ShiftWeeklySummaryItSupport {

    private static final Instant NOW = Instant.parse("2092-03-14T01:00:00Z");
    private static final LocalDate WEEK = mondayOnOrAfter(LocalDate.of(2092, 3, 14));
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
    private long manager;
    private long receptionist;
    private long vet;
    private long morning;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        manager = data.staff("BRANCH_MANAGER", branch);
        receptionist = data.staff("RECEPTIONIST", branch);
        vet = data.staff("VET", branch);
        morning = definition(branch, "Ca sáng", "08:00", "12:00", true);
    }

    @AfterEach
    void tearDown() {
        org.mockito.Mockito.reset(staffProfiles);
        finish();
    }

    @Test
    void runsInOneReadOnlyRepeatableReadTransactionAcrossTheStaffListsTheCountAndTheQuota() {
        data.assignment(branch, receptionist, morning, WEEK, "SCHEDULED", "SELF", null);
        List<String> states = new ArrayList<>();
        doAnswer(call -> {
            // Chạy trên luồng request: JdbcTemplate tham gia đúng transaction đang mở (ba lần, mỗi chức vụ một lần).
            states.add(jdbc.queryForObject(TX_STATE, String.class));
            return realCall(call);
        }).when(staffProfiles).findActiveStaffRows(any(), any());

        ResponseEntity<Map<String, Object>> response = summaries(token(manager), WEEK);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(states).as("một lần mỗi chức vụ A06–A08").hasSize(3).containsOnly("repeatable read|on");
    }

    @Test
    void aWriteCommittedAfterTheFirstStaffListIsNotSeenByThatCallButByTheNext() {
        String originalName = nameOf(receptionist);
        AtomicBoolean fired = new AtomicBoolean();
        doAnswer(call -> {
            Object result = realCall(call);
            if (fired.compareAndSet(false, true)) {
                // Luồng khác (autocommit), sau câu đầu tiên (ảnh chụp REPEATABLE READ đã chốt): các câu sau dưới
                // READ COMMITTED sẽ thấy cả tên mới lẫn phân ca mới.
                CompletableFuture.runAsync(() -> {
                    jdbc.update("UPDATE staff_profiles SET full_name = ? WHERE account_id = ?", "Tên đã đổi", receptionist);
                    data.assignment(branch, receptionist, morning, WEEK, "SCHEDULED", "SELF", null);
                    data.assignment(branch, vet, morning, WEEK, "SCHEDULED", "SELF", null);
                }).get(10, TimeUnit.SECONDS);
            }
            return result;
        }).when(staffProfiles).findActiveStaffRows(any(), any());

        ResponseEntity<Map<String, Object>> during = summaries(token(manager), WEEK);

        assertThat(fired).as("spy phải được gọi và kích hoạt đúng một lần").isTrue();
        assertThat(during.getStatusCode().value()).isEqualTo(200);
        Map<Long, Map<String, Object>> seen = byStaff(during);
        assertThat(seen.get(receptionist)).containsEntry("shiftCount", 0);
        assertThat(seen.get(vet)).containsEntry("shiftCount", 0);
        assertThat(seen.get(receptionist).get("staffName")).isEqualTo(originalName);

        Map<Long, Map<String, Object>> after = byStaff(summaries(token(manager), WEEK));

        assertThat(after.get(receptionist)).containsEntry("shiftCount", 1).containsEntry("staffName", "Tên đã đổi");
        assertThat(after.get(vet)).containsEntry("shiftCount", 1);
    }

    @Test
    void connectionsReturnToThePoolAsReadCommittedReadWrite() throws Exception {
        data.assignment(branch, receptionist, morning, WEEK, "SCHEDULED", "SELF", null);
        String token = token(manager);
        for (int i = 0; i < 5; i++) {
            assertThat(summaries(token, WEEK).getStatusCode().value()).isEqualTo(200);
            assertThat(summaries(token, WEEK.plusDays(1)).getStatusCode().value()).isEqualTo(400);
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
        data.assignment(branch, receptionist, morning, WEEK, "SCHEDULED", "SELF", null);
        String token = token(manager);
        String before = footprint(manager, receptionist, vet);
        AtomicBoolean failed = new AtomicBoolean();
        doAnswer(call -> {
            if (failed.compareAndSet(false, true)) {
                throw new DataAccessResourceFailureException("IT: DB tạm mất kết nối");
            }
            return realCall(call);
        }).when(staffProfiles).findActiveStaffRows(any(), any());

        ResponseEntity<Map<String, Object>> response = summaries(token, WEEK);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(errorCode(response)).isEqualTo("INTERNAL_ERROR");
        assertThat(dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(footprint(manager, receptionist, vet)).isEqualTo(before);
        assertThat(summaries(token, WEEK).getStatusCode().value()).as("lần sau bình thường").isEqualTo(200);
    }

    @Test
    void aBusinessRuleFailureWritesNothingAndHoldsNoConnection() throws Exception {
        data.assignment(branch, receptionist, morning, WEEK, "SCHEDULED", "SELF", null);
        String token = token(manager);
        String before = footprint(manager, receptionist, vet);

        assertBusinessRule(summaries(token, WEEK.plusDays(2)), "BR-NS-11");
        assertBusinessRule(summaries(token, firstMondayOf(2101)), "BR-NS-11");

        assertThat(dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(footprint(manager, receptionist, vet)).isEqualTo(before);
    }

    /** Gọi bản thật của repository (spy mặc định) thay vì stub; cùng cách dùng ở các IT khác của dự án. */
    private Object realCall(InvocationOnMock call) throws Throwable {
        return mockingDetails(staffProfiles).getMockCreationSettings().getDefaultAnswer().answer(call);
    }

    private String nameOf(long accountId) {
        return jdbc.queryForObject("SELECT full_name FROM staff_profiles WHERE account_id = ?", String.class, accountId);
    }
}
