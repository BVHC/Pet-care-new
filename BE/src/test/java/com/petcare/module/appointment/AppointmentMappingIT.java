package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.appointment.entity.AppointmentCancelSource;
import com.petcare.module.appointment.entity.AppointmentChannel;
import com.petcare.module.appointment.entity.AppointmentStatus;
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.catalog.api.ServiceGroup;

/**
 * Entity {@link Appointment} khớp từng cột của {@code appointments} (V1, erd §5; docs/adr/0041): một dòng chèn bằng SQL,
 * mỗi cột một giá trị riêng, đọc lại qua repository — map nhầm cột hay sai kiểu thì đỏ. Enum Java phải bằng đúng tập
 * giá trị của CHECK trong DB ({@code service_group} chỉ là tập con MEDICAL, GROOMING của {@code ServiceGroup}).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AppointmentMappingIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AppointmentRepository appointments;

    private AppointmentItSupport data;

    @BeforeEach
    void setUp() {
        data = new AppointmentItSupport(jdbc);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void everyColumnMapsToItsOwnField() {
        long branch = data.branch("DRAFT");
        long customer = data.counterCustomer();
        long pet = data.pet(customer);
        long service = data.service("GROOMING");
        long staff = data.receptionist();
        String code = "LH-IT-" + UUID.randomUUID().toString().substring(0, 8);
        Instant cancelledAt = Instant.parse("2030-01-01T02:03:04Z");
        Instant remindedAt = Instant.parse("2030-01-01T01:02:03Z");
        long id = data.appointment(jdbc.queryForObject("""
                INSERT INTO appointments (code, customer_id, pet_id, branch_id, service_id, service_group, slot_date,
                        slot_start, status, channel, booked_by, reschedule_count, late_cancel, cancel_source,
                        cancel_reason, cancelled_at, reminded_at, note)
                VALUES (?, ?, ?, ?, ?, 'GROOMING', DATE '2030-01-02', TIME '09:30', 'CANCELLED', 'COUNTER', ?, 2, true,
                        'STAFF', 'Khách đổi ý', ?, ?, 'Ghi chú của khách')
                RETURNING id""", Long.class, code, customer, pet, branch, service, staff,
                Timestamp.from(cancelledAt), Timestamp.from(remindedAt)));

        Appointment row = appointments.findById(id).orElseThrow();

        assertThat(row.getId()).isEqualTo(id);
        assertThat(row.getCode()).isEqualTo(code);
        assertThat(row.getCustomerId()).isEqualTo(customer);
        assertThat(row.getPetId()).isEqualTo(pet);
        assertThat(row.getBranchId()).isEqualTo(branch);
        assertThat(row.getServiceId()).isEqualTo(service);
        assertThat(row.getServiceGroup()).isEqualTo(ServiceGroup.GROOMING);
        assertThat(row.getSlotDate()).isEqualTo(LocalDate.of(2030, 1, 2));
        assertThat(row.getSlotStart()).isEqualTo(LocalTime.of(9, 30));
        assertThat(row.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(row.getChannel()).isEqualTo(AppointmentChannel.COUNTER);
        assertThat(row.getBookedBy()).isEqualTo(staff);
        assertThat(row.getRescheduleCount()).isEqualTo((short) 2);
        assertThat(row.isLateCancel()).isTrue();
        assertThat(row.getCancelSource()).isEqualTo(AppointmentCancelSource.STAFF);
        assertThat(row.getCancelReason()).isEqualTo("Khách đổi ý");
        assertThat(row.getCancelledAt()).isEqualTo(cancelledAt);
        assertThat(row.getRemindedAt()).isEqualTo(remindedAt);
        assertThat(row.getNote()).isEqualTo("Ghi chú của khách");
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getUpdatedAt()).isNotNull();
    }

    @Test
    void nullableColumnsMapToNull() {
        long branch = data.branch("DRAFT");
        long customer = data.counterCustomer();
        long pet = data.pet(customer);
        long service = data.service("MEDICAL");
        long staff = data.receptionist();
        long id = data.appointment(jdbc.queryForObject("""
                INSERT INTO appointments (code, customer_id, pet_id, branch_id, service_id, service_group, slot_date,
                        slot_start, status, channel, booked_by)
                VALUES (?, ?, ?, ?, ?, 'MEDICAL', DATE '2030-01-03', TIME '08:15', 'BOOKED', 'ONLINE', ?)
                RETURNING id""", Long.class, "LH-IT-" + UUID.randomUUID().toString().substring(0, 8),
                customer, pet, branch, service, staff));

        Appointment row = appointments.findById(id).orElseThrow();

        assertThat(row.getStatus()).isEqualTo(AppointmentStatus.BOOKED);
        assertThat(row.getChannel()).isEqualTo(AppointmentChannel.ONLINE);
        assertThat(row.getServiceGroup()).isEqualTo(ServiceGroup.MEDICAL);
        assertThat(row.getSlotStart()).isEqualTo(LocalTime.of(8, 15));
        assertThat(row.getRescheduleCount()).isZero();
        assertThat(row.isLateCancel()).isFalse();
        assertThat(row.getCancelSource()).isNull();
        assertThat(row.getCancelReason()).isNull();
        assertThat(row.getCancelledAt()).isNull();
        assertThat(row.getRemindedAt()).isNull();
        assertThat(row.getNote()).isNull();
    }

    @Test
    void enumsMatchCheckConstraints() {
        assertThat(checkValues("ck_appointments_status")).isEqualTo(names(AppointmentStatus.values()));
        assertThat(checkValues("ck_appointments_channel")).isEqualTo(names(AppointmentChannel.values()));
        assertThat(checkValues("ck_appointments_cancel_source")).isEqualTo(names(AppointmentCancelSource.values()));
        assertThat(checkValues("ck_appointments_service_group")).isEqualTo(Set.of("MEDICAL", "GROOMING"))
                .isSubsetOf(names(ServiceGroup.values()));
    }

    private Set<String> checkValues(String constraint) {
        String definition = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?", String.class, constraint);
        Set<String> values = new TreeSet<>();
        Matcher matcher = Pattern.compile("'([A-Z_]+)'").matcher(definition);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static Set<String> names(Enum<?>[] values) {
        return new TreeSet<>(Arrays.stream(values).map(Enum::name).toList());
    }
}
