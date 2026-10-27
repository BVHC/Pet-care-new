package com.petcare.module.visit;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.petcare.module.appointment.AppointmentItSupport;

/**
 * Dữ liệu dùng chung của IT ST04 (docs/adr/0082): hai chi nhánh, một VET, ba loại khách theo BR-TB-02 (tài khoản đã xác
 * thực, hồ sơ tại quầy có email, hồ sơ tại quầy không email), danh mục vaccine một loại, và các hàm dựng lượt
 * {@code COMPLETED} / mũi tiêm / bệnh án. Mỗi thú có <b>tên riêng</b> để tra outbox theo payload {@code ten_thu_cung}.
 * Cùng {@link VaccinationItSupport} / {@link AppointmentItSupport}: chèn thẳng bằng SQL, {@link #cleanUp()} xóa theo thứ tự
 * FK {@code RESTRICT}.
 */
final class ReminderItFixture {

    final JdbcTemplate jdbc;
    final AppointmentItSupport data;
    final VaccinationItSupport vaccines;

    private final Map<Long, String> petNames = new LinkedHashMap<>();
    private final List<Long> branches = new ArrayList<>();
    private final List<String> emails = new ArrayList<>();

    long branch;
    long otherBranch;
    long vet;
    /** Khách có tài khoản đã xác thực (hồ sơ online, {@code customers.email = null}). */
    long account;
    String accountEmail;
    long online;
    /** Hồ sơ tại quầy có email. */
    long counter;
    String counterEmail;
    /** Hồ sơ tại quầy không email: không kênh nào, có SĐT. */
    long silent;
    long type;
    long protocol;
    long product;
    long lot;

    ReminderItFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.data = new AppointmentItSupport(jdbc);
        this.vaccines = new VaccinationItSupport(jdbc);
    }

    void setUp() {
        branch = branch();
        otherBranch = branch();
        vet = data.staff("VET", branch);
        accountEmail = "st04-" + UUID.randomUUID() + "@example.com";
        account = data.customerAccount(accountEmail, "ACTIVE");
        online = data.onlineCustomer(account, "Trần Thị Online");
        counterEmail = "st04-quay-" + UUID.randomUUID() + "@example.com";
        counter = data.counterCustomer("Lê Văn Quầy", counterEmail);
        silent = data.counterCustomer("Phạm Không Email", null);
        emails.add(accountEmail);
        emails.add(counterEmail);
        type = vaccines.vaccineType("DOG", "Dại ST04");
        protocol = vaccines.protocol("DOG", type, 1, 365, false);
        product = vaccines.product(type, "Rabisin", true);
        lot = vaccines.lot(branch, product);
    }

    private long branch() {
        long id = data.branch("ACTIVE");
        branches.add(id);
        return id;
    }

    /** Thú của {@code customerId} với tên riêng. */
    long pet(long customerId) {
        long id = data.pet(customerId);
        String name = "Pet-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("UPDATE pets SET name = ? WHERE id = ?", name, id);
        petNames.put(id, name);
        return id;
    }

    String petName(long petId) {
        return petNames.get(petId);
    }

    /** Lượt {@code COMPLETED} tại {@code branchId} do {@link #vet} phụ trách (chèn thẳng, không qua tiếp nhận). */
    long completedVisit(long branchId, long customerId, long petId, Instant at) {
        return visit(branchId, customerId, petId, "COMPLETED", at);
    }

    long visit(long branchId, long customerId, long petId, String status, Instant at) {
        return jdbc.queryForObject("""
                INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                        queue_sort_key, assignee_id, checked_in_by, checked_in_at, called_at, completed_at)
                VALUES (?, ?, ?, ?, 'MEDICAL', ?, 'WALK_IN', ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                "VS-IT-" + UUID.randomUUID().toString().substring(0, 8), branchId, customerId, petId, status,
                Timestamp.from(at), vet, vet, Timestamp.from(at), Timestamp.from(at),
                "COMPLETED".equals(status) ? Timestamp.from(at) : null);
    }

    /** Mũi tiêm của {@code visitId} đến hạn tái chủng {@code due}, loại {@link #type}; chưa bị thay thế, chưa nhắc. */
    long dose(long visitId, LocalDate due) {
        return dose(visitId, type, protocol, due);
    }

    long dose(long visitId, long typeId, long protocolId, LocalDate due) {
        return vaccines.dose(visitId, typeId, protocolId, 1, product, lot, due.minusDays(365), due, vet, null);
    }

    /** Bệnh án của lượt có hẹn tái khám {@code followUp}; {@code locked} = lượt đã hoàn tất (erd {@code locked_at}). */
    void medicalRecord(long visitId, LocalDate followUp, Instant lockedAt) {
        jdbc.update("""
                INSERT INTO medical_records (visit_id, follow_up_date, locked_at) VALUES (?, ?, ?)""", visitId, followUp,
                lockedAt == null ? null : Timestamp.from(lockedAt));
    }

    /** Hàng outbox của mẫu {@code codes} cho thú {@code petId}: kênh, người nhận, link, payload. */
    List<Map<String, Object>> outbox(long petId, String... codes) {
        String in = String.join(", ", java.util.Collections.nCopies(codes.length, "?"));
        List<Object> args = new ArrayList<>(List.of((Object[]) codes));
        args.add(petNames.get(petId));
        return jdbc.queryForList("""
                SELECT template_code, channel, recipient_account_id, recipient_email, status,
                       payload->>'link_url' AS link_url, payload::text AS payload
                FROM notification_outbox WHERE template_code IN (%s) AND payload->>'ten_thu_cung' = ?
                ORDER BY id""".formatted(in), args.toArray());
    }

    List<Map<String, Object>> tasks(long petId) {
        return jdbc.queryForList("""
                SELECT id, task_type, status, branch_id, customer_id, due_date, vaccination_id, medical_record_id
                FROM care_tasks WHERE pet_id = ? ORDER BY id""", petId);
    }

    long taskCount() {
        if (petNames.isEmpty()) {
            return 0;
        }
        String in = String.join(", ", java.util.Collections.nCopies(petNames.size(), "?"));
        return jdbc.queryForObject("SELECT count(*) FROM care_tasks WHERE pet_id IN (" + in + ")", Long.class,
                petNames.keySet().toArray());
    }

    /** Xóa theo thứ tự FK: Care Task, bệnh án, mũi / Order, lượt, outbox / thông báo, danh mục, rồi dữ liệu gốc. */
    void cleanUp() {
        for (long petId : petNames.keySet()) {
            jdbc.update("DELETE FROM care_tasks WHERE pet_id = ?", petId);
            vaccines.deleteDosesOfPet(petId);
            vaccines.deletePrescriptionsOfPet(petId);
        }
        vaccines.deleteDoses();
        for (long b : branches) {
            jdbc.update("DELETE FROM visits WHERE branch_id = ?", b);
        }
        for (String name : petNames.values()) {
            jdbc.update("DELETE FROM notification_outbox WHERE payload->>'ten_thu_cung' = ?", name);
        }
        jdbc.update("DELETE FROM notifications WHERE account_id = ?", account);
        vaccines.deleteCatalog();
        data.cleanUp();
    }
}
