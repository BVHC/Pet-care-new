package com.petcare;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PetcareApplicationTests {

    @Test
    void contextLoads() {
        // Verify Spring context loads successfully
    }
}
