package com.petcare.module.workforce.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

/** Bất biến tự thân của ca làm việc (BR-NS-01): tên và thứ tự giờ. Trùng tên / ca còn phân ca do service kiểm. */
class ShiftDefinitionTest {

    private static final LocalTime EIGHT = LocalTime.of(8, 0);
    private static final LocalTime TWELVE = LocalTime.of(12, 0);

    @Test
    void createStoresStrippedNameAndStartsActive() {
        ShiftDefinition definition = ShiftDefinition.create(7L, "  Ca sáng  ", EIGHT, TWELVE);

        assertThat(definition.getBranchId()).isEqualTo(7L);
        assertThat(definition.getName()).isEqualTo("Ca sáng");
        assertThat(definition.getStartTime()).isEqualTo(EIGHT);
        assertThat(definition.getEndTime()).isEqualTo(TWELVE);
        assertThat(definition.isActive()).isTrue();
    }

    @Test
    void createRejectsBlankOrTooLongName() {
        assertThatThrownBy(() -> ShiftDefinition.create(7L, "   ", EIGHT, TWELVE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShiftDefinition.create(7L, "x".repeat(ShiftDefinition.NAME_MAX_LENGTH + 1), EIGHT,
                TWELVE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ShiftDefinition.create(7L, "x".repeat(ShiftDefinition.NAME_MAX_LENGTH), EIGHT, TWELVE).getName())
                .hasSize(ShiftDefinition.NAME_MAX_LENGTH);
    }

    @Test
    void createRejectsStartNotBeforeEnd() {
        assertThatThrownBy(() -> ShiftDefinition.create(7L, "Ca", TWELVE, EIGHT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShiftDefinition.create(7L, "Ca", EIGHT, EIGHT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changeTimesRejectsInvalidOrderAndKeepsOldValues() {
        ShiftDefinition definition = ShiftDefinition.create(7L, "Ca", EIGHT, TWELVE);

        assertThatThrownBy(() -> definition.changeTimes(TWELVE, EIGHT)).isInstanceOf(IllegalArgumentException.class);

        assertThat(definition.getStartTime()).isEqualTo(EIGHT);
        assertThat(definition.getEndTime()).isEqualTo(TWELVE);
        definition.changeTimes(LocalTime.of(13, 0), LocalTime.of(17, 0));
        assertThat(definition.getStartTime()).isEqualTo(LocalTime.of(13, 0));
    }

    @Test
    void renameRejectsBlankAndKeepsOldName() {
        ShiftDefinition definition = ShiftDefinition.create(7L, "Ca", EIGHT, TWELVE);

        assertThatThrownBy(() -> definition.rename(" ")).isInstanceOf(IllegalArgumentException.class);

        assertThat(definition.getName()).isEqualTo("Ca");
        definition.rename(" Ca chiều ");
        assertThat(definition.getName()).isEqualTo("Ca chiều");
    }

    @Test
    void deactivateAndActivateToggleFlag() {
        ShiftDefinition definition = ShiftDefinition.create(7L, "Ca", EIGHT, TWELVE);

        definition.deactivate();
        assertThat(definition.isActive()).isFalse();
        definition.activate();
        assertThat(definition.isActive()).isTrue();
    }
}
