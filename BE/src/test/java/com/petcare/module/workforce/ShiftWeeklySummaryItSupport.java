package com.petcare.module.workforce;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * Nền chung của các IT thống kê tuần (workforce-v1 #16, docs/adr/0149 mục "Thống kê tuần"): đường dẫn, khóa JSON của
 * hợp đồng, gọi với query thô (URI dựng sẵn, không để {@code RestTemplate} mã hóa lại {@code %2B}) và hàm đổi tên nhân
 * viên. Mỗi lớp con có era riêng (xem docs/architecture/testing-notes.md) và gọi {@link #begin} / {@link #finish}.
 */
abstract class ShiftWeeklySummaryItSupport extends ShiftRegistrationItSupport {

    protected static final String SUMMARIES = "/api/shift-weekly-summaries";
    protected static final List<String> SUMMARY_KEYS = List.of("staffId", "staffName", "role", "shiftCount",
            "requiredShifts", "meetsRequirement");

    protected ResponseEntity<Map<String, Object>> summaries(String token, Object weekStart) {
        return call(HttpMethod.GET, SUMMARIES + "?weekStart=" + weekStart, token);
    }

    /** Gọi với phần query thô (rỗng = không có query); {@code URI.create} giữ nguyên {@code %2B}, {@code %20}. */
    protected ResponseEntity<Map<String, Object>> summariesRaw(String token, String rawQuery) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        URI uri = URI.create(http.getRootUri() + SUMMARIES + (rawQuery.isEmpty() ? "" : "?" + rawQuery));
        return http.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), MAP);
    }

    protected void rename(long staffId, String fullName) {
        jdbc.update("UPDATE staff_profiles SET full_name = ? WHERE account_id = ?", fullName, staffId);
    }

    /** Dòng của từng nhân viên theo {@code staffId}, giữ thứ tự của response. */
    protected static Map<Long, Map<String, Object>> byStaff(ResponseEntity<Map<String, Object>> response) {
        Map<Long, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map<String, Object> row : listOf(response)) {
            rows.put(idOf(row, "staffId"), row);
        }
        return rows;
    }
}
