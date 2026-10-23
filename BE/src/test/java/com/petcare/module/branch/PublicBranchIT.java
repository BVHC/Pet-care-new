package com.petcare.module.branch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
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

import com.petcare.TestcontainersConfiguration;

/**
 * UC15 — chi nhánh công khai (branch-v1 #20–21; BR-CK-01, BR-CN-05): chỉ chi nhánh ACTIVE, giờ đang áp dụng hôm nay
 * (không lấy bản tương lai), chi nhánh DRAFT trả 404, bộ lọc cấp cứu ngoài giờ, token gửi kèm bị bỏ qua.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PublicBranchIT {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String PATH = "/api/public/branches";

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    private String tag;
    private long active;
    private long emergency;
    private long draft;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        active = branch("B-" + tag, "ACTIVE", false);
        emergency = branch("A-" + tag, "ACTIVE", true);
        draft = branch("C-" + tag, "DRAFT", false);
        LocalDate today = LocalDate.now(ZONE);
        hours(active, today.minusDays(10), "08:00", "17:00");
        hours(active, today.plusDays(5), "09:00", "12:00");
    }

    @AfterEach
    void cleanBranches() {
        for (long id : new long[] {active, emergency, draft}) {
            jdbc.update("DELETE FROM opening_hours WHERE branch_id = ?", id);
            jdbc.update("DELETE FROM branches WHERE id = ?", id);
        }
    }

    private long branch(String name, String status, boolean emergencyFlag) {
        return jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status, accepts_after_hours_emergency)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, ?, ?) RETURNING id
                """, Long.class, name, status, emergencyFlag);
    }

    /** Cả 7 ngày; thứ Chủ nhật (7) nghỉ. */
    private void hours(long branchId, LocalDate from, String open, String close) {
        for (int day = 1; day <= 6; day++) {
            jdbc.update("INSERT INTO opening_hours (branch_id, effective_from, day_of_week, open_1, close_1) "
                    + "VALUES (?, ?, ?, CAST(? AS time), CAST(? AS time))", branchId, from, day, open, close);
        }
        jdbc.update("INSERT INTO opening_hours (branch_id, effective_from, day_of_week) VALUES (?, ?, 7)",
                branchId, from);
    }

    private ResponseEntity<Map<String, Object>> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() { });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String path) {
        ResponseEntity<Map<String, Object>> response = get(path, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    private List<Long> ours(List<Map<String, Object>> branches) {
        return branches.stream().map(b -> ((Number) b.get("branchId")).longValue())
                .filter(id -> id == active || id == emergency || id == draft).toList();
    }

    @Test
    void onlyActiveBranchesSortedByName() {
        assertThat(ours(list(PATH))).containsExactly(emergency, active);
    }

    @Test
    void emergencyFilterTrueAndFalse() {
        assertThat(ours(list(PATH + "?acceptsAfterHoursEmergency=true"))).containsExactly(emergency);
        assertThat(ours(list(PATH + "?acceptsAfterHoursEmergency=false"))).containsExactly(active);
    }

    @Test
    @SuppressWarnings("unchecked")
    void weeklyHoursAreTodaysVersionWithSevenDays() {
        Map<String, Object> found = list(PATH).stream()
                .filter(b -> ((Number) b.get("branchId")).longValue() == active).findFirst().orElseThrow();

        assertThat(found).containsOnlyKeys("branchId", "name", "address", "phone", "latitude", "longitude",
                "acceptsAfterHoursEmergency", "weeklyHours");
        List<Map<String, Object>> weekly = (List<Map<String, Object>>) found.get("weeklyHours");
        assertThat(weekly).hasSize(7);
        assertThat(weekly.stream().map(d -> d.get("dayOfWeek")).toList()).containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat((List<?>) weekly.get(6).get("ranges")).isEmpty();
        assertThat((List<Map<String, Object>>) weekly.get(0).get("ranges")).containsExactly(
                Map.of("open", "08:00", "close", "17:00"));
    }

    @Test
    void detailOfAnActiveBranchIsReturned() {
        ResponseEntity<Map<String, Object>> response = get(PATH + "/" + active, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(((Number) data.get("branchId")).longValue()).isEqualTo(active);
    }

    @Test
    void draftAndUnknownBranchDetailAre404() {
        assertThat(get(PATH + "/" + draft, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(PATH + "/999999999", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aGarbageBearerTokenIsIgnoredOnAPublicPath() {
        assertThat(get(PATH + "/" + active, "not-a-token").getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
