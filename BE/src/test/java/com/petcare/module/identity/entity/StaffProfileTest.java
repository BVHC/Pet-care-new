package com.petcare.module.identity.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/** UC06 — {@link StaffProfile#updateSelfProfile}: gán đúng bốn giá trị cuối, không đổi chi nhánh (docs/adr/0026). */
class StaffProfileTest {

    @Test
    void updateSelfProfileAssignsFinalValuesAndKeepsBranch() {
        StaffProfile profile = profile();

        profile.updateSelfProfile("Bác sĩ An", null, "Nội khoa", null);

        assertThat(profile.getFullName()).isEqualTo("Bác sĩ An");
        assertThat(profile.getAvatarUrl()).isNull();
        assertThat(profile.getSpecialty()).isEqualTo("Nội khoa");
        assertThat(profile.getBio()).isNull();
        assertThat(profile.getBranchId()).isEqualTo(3L);
        assertThat(profile.getAccountId()).isEqualTo(7L);
    }

    @Test
    void fullNameIsRequired() {
        StaffProfile profile = profile();

        assertThatThrownBy(() -> profile.updateSelfProfile(null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile.updateSelfProfile("  ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(profile.getFullName()).isEqualTo("Tên cũ");
        assertThat(profile.getAvatarUrl()).isEqualTo("https://img/old.png");
    }

    private static StaffProfile profile() {
        StaffProfile profile = BeanUtils.instantiateClass(StaffProfile.class);
        ReflectionTestUtils.setField(profile, "accountId", 7L);
        ReflectionTestUtils.setField(profile, "fullName", "Tên cũ");
        ReflectionTestUtils.setField(profile, "avatarUrl", "https://img/old.png");
        ReflectionTestUtils.setField(profile, "branchId", 3L);
        return profile;
    }
}
