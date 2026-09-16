package com.petcare.platform.bootstrap;

import com.petcare.module.auth.entity.Account;
import com.petcare.module.auth.repository.AccountRepository;
import com.petcare.module.iam.entity.User;
import com.petcare.module.iam.service.UserProvisioningService;
import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seed tài khoản mặc định cho môi trường phát triển (đồ án / demo).
 *
 * <p>Tài khoản seed được tạo thẳng ở trạng thái {@link AccountStatus#ACTIVE}
 * nên bỏ qua bước OTP — đăng nhập được ngay. Chỉ chạy khi
 * {@code app.dev-seed.enabled=true}; TẮT ở môi trường thật.
 *
 * <p>Idempotent: bỏ qua email đã tồn tại, nên chạy lại app không tạo trùng.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.dev-seed.enabled", havingValue = "true")
public class DevAccountSeeder implements ApplicationRunner {

    /** Mật khẩu chung cho mọi tài khoản seed — chỉ dùng ở môi trường dev. */
    private static final String DEFAULT_PASSWORD = "Petcare@123";

    private static final List<SeedAccount> SEEDS = List.of(
            new SeedAccount("customer@petcare.vn", "0901234567", "Nguyễn Văn An", UserRole.CUSTOMER),
            new SeedAccount("customer2@petcare.vn", "0901234568", "Trần Thị Bình", UserRole.CUSTOMER),
            new SeedAccount("reception@petcare.vn", "0902345678", "Lê Thu Hà", UserRole.RECEPTIONIST),
            new SeedAccount("vet@petcare.vn", "0903456789", "BS. Phạm Minh Quân", UserRole.VETERINARIAN),
            new SeedAccount("groomer@petcare.vn", "0904567890", "Đỗ Khánh Linh", UserRole.GROOMER),
            new SeedAccount("admin@petcare.vn", "0905678901", "Quản trị hệ thống", UserRole.SUPER_ADMIN)
    );

    private final AccountRepository accountRepository;
    private final UserProvisioningService userProvisioningService;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int created = 0;
        for (SeedAccount seed : SEEDS) {
            if (accountRepository.findByEmail(seed.email()).isPresent()) {
                continue;
            }
            Account account = new Account(seed.email(), seed.phone(), passwordEncoder.encode(DEFAULT_PASSWORD));
            account.setStatus(AccountStatus.ACTIVE);
            account = accountRepository.save(account);

            // createCustomerProfile luôn gán CUSTOMER — chỉnh lại role cho
            // nhân sự nội bộ; entity đang managed nên dirty checking tự flush.
            User user = userProvisioningService.createCustomerProfile(account.getId(), seed.fullName());
            user.setRole(seed.role());
            created++;
        }

        if (created > 0) {
            log.warn("DEV SEED: đã tạo {} tài khoản mặc định, mật khẩu chung '{}'. TẮT app.dev-seed.enabled ở production.",
                    created, DEFAULT_PASSWORD);
        } else {
            log.info("DEV SEED: tài khoản mặc định đã có sẵn, không tạo thêm.");
        }
    }

    private record SeedAccount(String email, String phone, String fullName, UserRole role) {
    }
}
