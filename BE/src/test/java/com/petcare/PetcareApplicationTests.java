package com.petcare;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PetcareApplicationTests {

    @Test
    void contextLoads() {
        // Context khởi động được nghĩa là Flyway đã áp migration lên Postgres 17 và Hibernate validate thành công
    }
}
