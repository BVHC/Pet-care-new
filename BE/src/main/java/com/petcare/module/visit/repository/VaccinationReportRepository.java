package com.petcare.module.visit.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.petcare.module.visit.api.VaccinationReportApi.ReturnRateRow;

/**
 * Tỷ lệ quay lại tái chủng (BR-BC-04; docs/adr/0087). Chỉ đọc, một câu gộp nhóm. Viết thay BE-1 bởi BE-2 ngày 30/10
 * (06 §6). <b>Lệch có chủ đích khỏi ranh giới module:</b> JOIN {@code pets} (customer) chỉ để đọc {@code deceased_on} cho
 * mẫu số "trừ thú mất trước ngày tái chủng" — một câu thay vì một lần gọi {@code PetQueryApi} mỗi mũi (06 Q3). Mũi trước
 * đã bị thay ({@code superseded_at}) vẫn ở mẫu số; mũi lại là mũi khác của cùng thú + cùng loại tiêm sau mũi trước và
 * không muộn quá {@code graceDays} sau ngày tái chủng (tiêm sớm cũng tính). Chi nhánh = chi nhánh của mũi trước.
 */
@Repository
public class VaccinationReportRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public VaccinationReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ReturnRateRow> revaccinationReturn(Long branchId, LocalDate from, LocalDate to, int graceDays) {
        MapSqlParameterSource params = new MapSqlParameterSource("from", from).addValue("to", to)
                .addValue("graceDays", graceDays);
        String branch = "";
        if (branchId != null) {
            branch = " AND v.branch_id = :branchId";
            params.addValue("branchId", branchId);
        }
        return jdbc.query("""
                SELECT v.branch_id, v.vaccine_type_id, count(*) AS due,
                       count(*) FILTER (WHERE EXISTS (
                           SELECT 1 FROM vaccinations w
                           WHERE w.pet_id = v.pet_id AND w.vaccine_type_id = v.vaccine_type_id AND w.id <> v.id
                             AND w.administered_on > v.administered_on
                             AND w.administered_on <= v.next_due_date + :graceDays)) AS returned
                FROM vaccinations v JOIN pets p ON p.id = v.pet_id
                WHERE v.next_due_date BETWEEN :from AND :to
                  AND (p.deceased_on IS NULL OR p.deceased_on >= v.next_due_date)""" + branch + """
                 GROUP BY v.branch_id, v.vaccine_type_id ORDER BY v.branch_id, v.vaccine_type_id
                """, params, (rs, i) -> new ReturnRateRow(rs.getLong("branch_id"), rs.getLong("vaccine_type_id"),
                Math.toIntExact(rs.getLong("due")), Math.toIntExact(rs.getLong("returned"))));
    }
}
