package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.support.MutableClock;

/**
 * Ảnh nhất quán của {@code GET /shift-requests} (workforce-v1 #12; docs/adr/0138 bước 8): trang, tổng và dữ liệu ghép
 * phải cùng một ảnh chụp REPEATABLE_READ. Spy repository của identity (không spy bean {@code @Transactional}, bẫy
 * Mockito): đúng lúc service ghép tên người gửi, tức <b>sau</b> khi câu trang và câu {@code count} đã chạy, một luồng
 * khác commit việc đổi tên người gửi và thêm một yêu cầu {@code PENDING}. Phản hồi phải mang tên <b>cũ</b> và đúng hai
 * dòng; lần gọi sau mới thấy tên mới và ba dòng. Dưới READ COMMITTED test này đỏ. Tách khỏi
 * {@code ShiftRequestBranchListIT} vì spy cần context riêng. Era <b>năm 2074</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRequestBranchListSnapshotIT.TestBeans.class})
class ShiftRequestBranchListSnapshotIT extends ShiftRequestItSupport {

    private static final Instant NOW = Instant.parse("2074-03-14T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2074, 3, 21);
    private static final OffsetDateTime T0 = OffsetDateTime.of(2074, 3, 1, 8, 0, 0, 0, ZoneOffset.UTC);
    private static final String LIST = "/api/shift-requests";

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

    private long branch;
    private long manager;
    private long staff;
    private long shift;
    private long first;
    private long second;
    private String originalName;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        manager = data.staff("BRANCH_MANAGER", branch);
        staff = data.staff("RECEPTIONIST", branch);
        shift = definition(branch, "Ca sáng", "08:00", "12:00", true);
        first = pendingOn(1, 0);
        second = pendingOn(2, 1);
        originalName = nameOf(staff);
    }

    @AfterEach
    void tearDown() {
        org.mockito.Mockito.reset(staffProfiles);
        finish();
    }

    @Test
    void aWriteCommittedWhileTheListIsBeingAssembledIsNotSeenByThatListButByTheNext() {
        AtomicBoolean fired = new AtomicBoolean();
        doAnswer(call -> {
            if (fired.compareAndSet(false, true)) {
                // Phải là luồng khác: JdbcTemplate ở luồng request sẽ tham gia transaction đang mở (không phải commit
                // độc lập). Luồng mới không có kết nối gắn sẵn nên mỗi câu autocommit, đúng một "giao dịch khác".
                CompletableFuture.runAsync(() -> {
                    jdbc.update("UPDATE staff_profiles SET full_name = ? WHERE account_id = ?", "Tên đã đổi", staff);
                    pendingOn(3, 2);
                }).get(10, TimeUnit.SECONDS);
            }
            return realCall(call);
        }).when(staffProfiles).findStaffRows(anyCollection());

        ResponseEntity<Map<String, Object>> during = call(HttpMethod.GET, LIST, token(manager));

        assertThat(fired).as("spy phải được gọi đúng một lần giữa lúc ghép").isTrue();
        assertThat(during.getStatusCode().value()).isEqualTo(200);
        assertThat(contentOf(dataOf(during))).extracting(item -> idOf(item, "shiftRequestId"))
                .containsExactly(first, second);
        assertThat(dataOf(during)).containsEntry("totalElements", 2);
        assertThat(contentOf(dataOf(during))).extracting(item -> item.get("requesterName"))
                .containsOnly(originalName);

        ResponseEntity<Map<String, Object>> after = call(HttpMethod.GET, LIST, token(manager));

        assertThat(dataOf(after)).containsEntry("totalElements", 3);
        assertThat(contentOf(dataOf(after))).hasSize(3);
        assertThat(contentOf(dataOf(after))).extracting(item -> item.get("requesterName")).containsOnly("Tên đã đổi");
    }

    /** Gọi bản thật của repository (spy mặc định) thay vì stub; cùng cách dùng ở các IT khác của dự án. */
    private Object realCall(InvocationOnMock call) throws Throwable {
        return mockingDetails(staffProfiles).getMockCreationSettings().getDefaultAnswer().answer(call);
    }

    private long pendingOn(int dayOffset, int hours) {
        long assignment = data.assignment(branch, staff, shift, DAY.plusDays(dayOffset), "SCHEDULED", "SELF", null);
        long id = data.leaveRequest(assignment, staff, "PENDING", null);
        jdbc.update("UPDATE shift_requests SET created_at = ? WHERE id = ?", T0.plusHours(hours), id);
        return id;
    }

    private String nameOf(long accountId) {
        return jdbc.queryForObject("SELECT full_name FROM staff_profiles WHERE account_id = ?", String.class,
                accountId);
    }
}
