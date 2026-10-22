package com.petcare.module.customer.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskersTest {

    @Test
    void fullNameFollowsTheBrTk19Example() {
        assertThat(Maskers.fullName("Nguyễn Văn A")).isEqualTo("Ng*** V** A");
    }

    @Test
    void fullNameLastWordShowsOnlyItsInitial() {
        assertThat(Maskers.fullName("Nguyễn Thị Lan Anh")).isEqualTo("Ng*** T** L** A");
        assertThat(Maskers.fullName("Lê Hoa")).isEqualTo("Lê*** H");
    }

    @Test
    void singleWordAndOddInput() {
        assertThat(Maskers.fullName("Minh")).isEqualTo("Mi***");
        assertThat(Maskers.fullName("A")).isEqualTo("A***");
        assertThat(Maskers.fullName("  Trần   Bình  ")).isEqualTo("Tr*** B");
        assertThat(Maskers.fullName("   ")).isEqualTo("***");
        assertThat(Maskers.fullName(null)).isEqualTo("***");
    }

    @Test
    void fullNameNeverLeaksTheRest() {
        assertThat(Maskers.fullName("Nguyễn Văn Anh")).doesNotContain("uyễn", "ăn", "nh");
    }

    @Test
    void emailKeepsTwoLeadingCharactersAndDomain() {
        assertThat(Maskers.email("nguyen.van.a@gmail.com")).isEqualTo("ng***@gmail.com");
        assertThat(Maskers.email("ab@x.vn")).isEqualTo("a***@x.vn");
        assertThat(Maskers.email("a@x.vn")).isEqualTo("a***@x.vn");
    }

    @Test
    void emailInvalidOrNull() {
        assertThat(Maskers.email(null)).isNull();
        assertThat(Maskers.email("no-at-sign")).isEqualTo("***");
        assertThat(Maskers.email("@x.vn")).isEqualTo("***");
        assertThat(Maskers.email("a@")).isEqualTo("***");
    }
}
