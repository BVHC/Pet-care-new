package com.petcare.module.identity.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.dto.StaffProfileResponse;
import com.petcare.module.identity.dto.UpdateStaffProfileRequest;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.entity.StaffProfile;
import com.petcare.module.identity.mapper.MeMapper;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;

/**
 * UC06 — nhân viên tự sửa hồ sơ ({@code PATCH /api/me/staff-profile}, identity-v1 #10; BR-TK-15, 20; docs/adr/0026).
 * Một transaction, không audit (convention 08 §8.3 không liệt kê).
 * <ol>
 *   <li>Có {@code email} → BR-TK-15, trước mọi đọc / ghi.</li>
 *   <li>Khóa dòng {@code accounts} ({@code FOR NO KEY UPDATE}) <b>rồi mới</b> đọc {@code staff_profiles}: Hibernate ghi
 *       đủ mọi cột, nên đọc dưới khóa thì không ghi đè thay đổi đã commit của luồng khác (thứ tự khóa
 *       {@code accounts → staff_profiles}; {@code touchLastSeen} dùng {@code SKIP LOCKED} nên không chờ).</li>
 *   <li>BR-TK-11 kiểm lại dưới khóa: tài khoản vừa bị khóa / vô hiệu hóa sau khi filter cho request qua (như
 *       {@code ChangePasswordAttemptService}, docs/adr/0022 mục 9).</li>
 *   <li>Không phải VET mà gửi {@code specialty} / {@code bio} (kể cả chuỗi rỗng) → 403.</li>
 *   <li>{@code bio} gửi lên và không rỗng: số ký tự (code point, như {@code VARCHAR} của Postgres) ≤
 *       {@code vet.bio_max_length} [CFG], ngược lại BR-TK-20. Không gửi {@code bio} thì không kiểm, để bio cũ dài hơn
 *       giới hạn vừa bị hạ không chặn việc sửa trường khác (BR-QT-13).</li>
 * </ol>
 * Mọi lần từ chối xảy ra trước lệnh ghi đầu tiên nên rollback sạch. {@code flush()} trước khi map để lỗi khóa / ràng
 * buộc của DB ném ra trong method (→ 409) thay vì lúc commit.
 */
@Service
public class StaffProfileService {

    private final AccountRepository accounts;
    private final StaffProfileRepository staffProfiles;
    private final SystemConfigApi configs;
    private final BranchScope branchScope;
    private final MeMapper mapper;

    public StaffProfileService(AccountRepository accounts, StaffProfileRepository staffProfiles,
            SystemConfigApi configs, BranchScope branchScope, MeMapper mapper) {
        this.accounts = accounts;
        this.staffProfiles = staffProfiles;
        this.configs = configs;
        this.branchScope = branchScope;
        this.mapper = mapper;
    }

    @Transactional
    public StaffProfileResponse updateStaffProfile(UpdateStaffProfileRequest request) {
        if (request.email() != null) {
            throw new BusinessRuleViolationException("BR-TK-15",
                    "Không thể tự sửa email; liên hệ cấp trên để được sửa hộ");
        }

        Long accountId = branchScope.current().accountId();
        Account account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new IllegalStateException("Authenticated account " + accountId + " not found"));
        StaffProfile profile = staffProfiles.findById(accountId)
                .orElseThrow(() -> new IllegalStateException(
                        "Staff account " + accountId + " has no staff_profiles row"));

        if (account.isLocked() || account.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessRuleViolationException("BR-TK-11", LoginAttemptService.MSG_LOCKED);
        }
        if (account.getRole() != Role.VET && (request.specialty() != null || request.bio() != null)) {
            throw new AccessDeniedScopeException(Role.VET.name(), account.getRole().name());
        }
        String bio = optionalValue(request.bio(), profile.getBio());
        if (request.bio() != null && bio != null) {
            int max = configs.getInt(ConfigKey.VET_BIO_MAX_LENGTH);
            if (bio.codePointCount(0, bio.length()) > max) {
                throw new BusinessRuleViolationException("BR-TK-20", "Mô tả ngắn tối đa " + max + " ký tự");
            }
        }

        profile.updateSelfProfile(
                request.fullName() == null ? profile.getFullName() : request.fullName().strip(),
                optionalValue(request.avatarUrl(), profile.getAvatarUrl()),
                optionalValue(request.specialty(), profile.getSpecialty()),
                bio);
        if (request.phone() != null) {
            account.changeStaffPhone(request.phone());
        }
        staffProfiles.flush();
        return mapper.toStaffProfileResponse(profile, account.getPhone());
    }

    /** Trường cho phép NULL: {@code null} = giữ {@code current}; rỗng sau {@code strip} = xóa; còn lại = giá trị mới. */
    private static String optionalValue(String requested, String current) {
        if (requested == null) {
            return current;
        }
        String stripped = requested.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
