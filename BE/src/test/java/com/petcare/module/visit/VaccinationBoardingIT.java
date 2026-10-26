package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.boarding.BoardingClockConfig;
import com.petcare.module.boarding.BoardingItSupport;

/**
 * Đặt chỗ lưu trú với {@code VaccinationQueryApi} <b>thật</b> (T28 phần 0, docs/adr/0072): {@code BoardingBookingIT} dùng
 * stand-in nên không chứng minh được bean thật. Cảnh báo BR-LT-05 không chặn đặt chỗ (201); mũi còn hạn thì không cảnh
 * báo. Kho dùng chung DB nên chỉ xét loại vaccine do test tạo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, BoardingClockConfig.class})
class VaccinationBoardingIT extends BoardingItSupport {

    private AppointmentItSupport appointments;
    private VaccinationItSupport vaccines;
    private long branch;
    private long kennelType;
    private long vet;
    private long customerId;
    private String customerToken;
    private long pet;
    private long vaccineType;

    @BeforeEach
    void setUp() {
        appointments = new AppointmentItSupport(jdbc);
        vaccines = new VaccinationItSupport(jdbc);
        branch = newBranch();
        kennelType = newKennelType(branch, "DOG", "30", 150_000);
        newKennel(branch, kennelType, "C-01", "AVAILABLE");
        vet = newStaff("VET", branch);
        long[] online = newOnlineCustomer();
        customerToken = token(online[0]);
        customerId = online[1];
        pet = newPet(customerId, "DOG");
        weigh(pet, "12.5");
        vaccineType = vaccines.vaccineType("DOG", "Dại lưu trú IT");
        vaccines.protocol("DOG", vaccineType, 1, 365, true);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM boarding_bookings WHERE pet_id = ?", pet);
        vaccines.deleteDoses();
        appointments.cleanUp();
        vaccines.deleteCatalog();
    }

    private Map<String, Object> book() {
        return data(call(HttpMethod.POST, "/api/boarding-bookings", customerToken,
                Map.of("petId", pet, "branchId", branch, "kennelTypeId", kennelType, "checkInDate", "2009-06-12",
                        "checkOutDate", "2009-06-15")), HttpStatus.CREATED);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> warningsOfMyType() {
        return ((List<Map<String, Object>>) book().get("vaccineWarnings")).stream()
                .filter(w -> ((Number) w.get("vaccineTypeId")).longValue() == vaccineType).toList();
    }

    @Test
    void aPetNeverVaccinatedIsBookedWithAWarningNotRejected() {
        List<Map<String, Object>> warnings = warningsOfMyType();

        assertThat(warnings).hasSize(1);
        assertThat((String) warnings.get(0).get("vaccineTypeName")).startsWith("Dại lưu trú IT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM boarding_bookings WHERE pet_id = ?", Long.class, pet))
                .isEqualTo(1L);
    }

    @Test
    void aValidDoseMeansNoWarning() {
        long service = appointments.service("MEDICAL");
        long appointment = appointments.appointment(customerId, pet, branch, service, "MEDICAL", TODAY,
                LocalTime.of(9, 0), "BOOKED", vet);
        long visit = appointments.visit(appointment, vet);
        long product = vaccines.product(vaccineType, "Rabisin", true);
        long lot = vaccines.lot(branch, product);
        long protocol = jdbc.queryForObject("SELECT id FROM vaccination_protocols WHERE vaccine_type_id = ?",
                Long.class, vaccineType);
        vaccines.dose(visit, vaccineType, protocol, 1, product, lot, TODAY.minusDays(10), LocalDate.of(2010, 6, 10), vet,
                null);

        assertThat(warningsOfMyType()).isEmpty();
    }
}
