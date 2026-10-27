package com.petcare.module.care.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.petcare.module.care.api.CareTaskApi.CareTaskType;
import com.petcare.module.care.api.CareTaskApi.NewCareTask;
import com.petcare.module.care.dto.CareTaskResponse;
import com.petcare.module.care.entity.CareTask;
import com.petcare.module.care.entity.CareTaskResult;
import com.petcare.module.care.entity.CareTaskStatus;

import io.swagger.v3.core.util.Yaml;

/**
 * {@link CareTaskMapper} (care-v1 schema {@code CareTask}, docs/adr/0080) với bản MapStruct sinh thật. Mỗi cột của
 * entity mang một giá trị riêng nên một trường map nhầm nguồn làm test đỏ. Parity IT chỉ so <b>tên</b> property, nên
 * tập giá trị enum và nullable được kiểm riêng ở đây (G14, G15).
 */
class CareTaskMapperTest {

    private static final Path CONTRACT = Path.of("..", "docs", "api", "openapi", "care-v1.yaml");
    private static final Instant CREATED = Instant.parse("2026-10-20T01:02:03.456789Z");
    private static final Instant HANDLED = Instant.parse("2026-10-21T04:05:06.123456Z");
    private static final LocalDate DUE = LocalDate.of(2026, 11, 3);

    private final CareTaskMapper mapper = new CareTaskMapperImpl();

    private static CareTask open(CareTaskType type) {
        CareTask task = CareTask.open(new NewCareTask(type, 500L, 11L, 22L, 33L, DUE));
        ReflectionTestUtils.setField(task, "id", 311L);
        ReflectionTestUtils.setField(task, "createdAt", CREATED);
        return task;
    }

    @ParameterizedTest
    @EnumSource(CareTaskType.class)
    void anOpenTaskMapsEveryFieldFromItsOwnSourceAndHasNoHandlingData(CareTaskType type) {
        CareTaskResponse response = mapper.toResponse(open(type), "Nguyễn Văn An", "0901111111", "Mực", "ngữ cảnh");

        assertThat(response.careTaskId()).isEqualTo(311L);
        assertThat(response.type()).isEqualTo(type);
        assertThat(response.status()).isEqualTo(CareTaskStatus.OPEN);
        assertThat(response.branchId()).isEqualTo(11L);
        assertThat(response.customerId()).isEqualTo(22L);
        assertThat(response.customerName()).isEqualTo("Nguyễn Văn An");
        assertThat(response.phoneToCall()).isEqualTo("0901111111");
        assertThat(response.petId()).isEqualTo(33L);
        assertThat(response.petName()).isEqualTo("Mực");
        assertThat(response.dueDate()).isEqualTo(DUE);
        assertThat(response.context()).isEqualTo("ngữ cảnh");
        assertThat(response.createdAt()).isEqualTo(CREATED);
        assertThat(response.result()).isNull();
        assertThat(response.note()).isNull();
        assertThat(response.cancelReason()).isNull();
        assertThat(response.handledBy()).isNull();
        assertThat(response.handledAt()).isNull();
        // Đúng một id nguồn, và id Visit của bệnh án đi ra tên medicalRecordVisitId.
        boolean vaccine = type == CareTaskType.VACCINE_DUE || type == CareTaskType.VACCINE_OVERDUE;
        assertThat(response.vaccinationId()).isEqualTo(vaccine ? 500L : null);
        assertThat(response.medicalRecordVisitId()).isEqualTo(type == CareTaskType.FOLLOW_UP_DUE ? 500L : null);
        assertThat(response.boardingBookingId()).isEqualTo(type == CareTaskType.PICKUP_OVERDUE ? 500L : null);
        assertThat(Arrays.asList(response.vaccinationId(), response.medicalRecordVisitId(),
                response.boardingBookingId())).filteredOn(java.util.Objects::nonNull).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(CareTaskResult.class)
    void aDoneTaskShowsResultNoteAndTheReceptionistButNoCancelReason(CareTaskResult result) {
        CareTask task = open(CareTaskType.VACCINE_DUE);
        task.complete(result, "Ghi chú cuộc gọi", 21L, HANDLED);

        CareTaskResponse response = mapper.toResponse(task, "A", null, "B", "C");

        assertThat(response.status()).isEqualTo(CareTaskStatus.DONE);
        assertThat(response.result()).isEqualTo(result);
        assertThat(response.note()).isEqualTo("Ghi chú cuộc gọi");
        assertThat(response.handledBy()).isEqualTo(21L);
        assertThat(response.handledAt()).isEqualTo(HANDLED);
        assertThat(response.cancelReason()).isNull();
        assertThat(response.phoneToCall()).isNull();
    }

    @Test
    void aTaskCancelledByTheReceptionistShowsHerIdAndOneCancelledBySystemShowsNone() {
        CareTask byStaff = open(CareTaskType.FOLLOW_UP_DUE);
        byStaff.cancel("Khách chuyển đi", 21L, HANDLED);
        // Hủy do hệ thống (#4–#6): UPDATE ghi lý do + handled_at, handled_by để NULL.
        CareTask bySystem = open(CareTaskType.VACCINE_OVERDUE);
        ReflectionTestUtils.setField(bySystem, "status", CareTaskStatus.CANCELLED);
        ReflectionTestUtils.setField(bySystem, "cancelReason", "Đã tiêm lại (BR-TB-03)");
        ReflectionTestUtils.setField(bySystem, "handledAt", HANDLED);

        CareTaskResponse staff = mapper.toResponse(byStaff, "A", "0901", "B", "C");
        CareTaskResponse system = mapper.toResponse(bySystem, "A", "0901", "B", "C");

        assertThat(staff.status()).isEqualTo(CareTaskStatus.CANCELLED);
        assertThat(staff.cancelReason()).isEqualTo("Khách chuyển đi");
        assertThat(staff.handledBy()).isEqualTo(21L);
        assertThat(staff.result()).isNull();
        assertThat(staff.note()).isNull();
        assertThat(system.cancelReason()).isEqualTo("Đã tiêm lại (BR-TB-03)");
        assertThat(system.handledBy()).isNull();
        assertThat(system.handledAt()).isEqualTo(HANDLED);
    }

    @Test
    void theResponseHasExactlyTheContractFieldsAndNoUpdatedAt() throws IOException {
        JsonNode schema = contract().at("/components/schemas/CareTask");
        Set<String> contractFields = names(schema.path("properties").fieldNames());
        Set<String> required = new LinkedHashSet<>();
        schema.path("required").forEach(n -> required.add(n.asText()));

        Set<String> recordFields = Arrays.stream(CareTaskResponse.class.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(recordFields).containsExactlyInAnyOrderElementsOf(contractFields).doesNotContain("updatedAt");
        assertThat(required).isSubsetOf(contractFields);
    }

    @Test
    void theEnumsOfTheResponseAndRequestsEqualTheContractValueSets() throws IOException {
        JsonNode root = contract();

        assertThat(values(root.at("/components/schemas/CareTaskType/enum")))
                .containsExactlyInAnyOrderElementsOf(constants(CareTaskType.values()));
        assertThat(values(root.at("/components/schemas/CareTask/properties/status/enum")))
                .containsExactlyInAnyOrderElementsOf(constants(CareTaskStatus.values()));
        assertThat(values(root.at("/components/schemas/CareTask/properties/result/enum")))
                .containsExactlyInAnyOrderElementsOf(constants(CareTaskResult.values()));
        assertThat(values(root.at("/components/schemas/CompleteCareTaskRequest/properties/result/enum")))
                .containsExactlyInAnyOrderElementsOf(constants(CareTaskResult.values()));
    }

    @Test
    void theRequestLimitsEqualTheContractAndTheColumn() throws IOException {
        JsonNode root = contract();

        assertThat(root.at("/components/schemas/CompleteCareTaskRequest/properties/note/maxLength").asInt())
                .isEqualTo(2000);
        assertThat(root.at("/components/schemas/CancelCareTaskRequest/properties/reason/maxLength").asInt())
                .isEqualTo(CareTask.CANCEL_REASON_MAX_LENGTH);
    }

    private static JsonNode contract() throws IOException {
        return Yaml.mapper().readTree(CONTRACT.toFile());
    }

    private static Set<String> names(java.util.Iterator<String> iterator) {
        Set<String> names = new LinkedHashSet<>();
        iterator.forEachRemaining(names::add);
        return names;
    }

    private static Set<String> values(JsonNode array) {
        // Enum nullable trong yaml có thêm phần tử "null" (cột có thể NULL): đó không phải một hằng số của enum Java.
        return StreamSupport.stream(array.spliterator(), false).filter(n -> !n.isNull()).map(JsonNode::asText)
                .filter(v -> !"null".equals(v)).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> constants(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
