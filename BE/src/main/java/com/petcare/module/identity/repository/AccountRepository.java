package com.petcare.module.identity.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.identity.entity.Account;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * BR-TN-06: ghi {@code last_seen_at} nếu giá trị cũ trước {@code threshold}. {@code SKIP LOCKED}: dòng đang bị
     * transaction khác khóa (khóa tài khoản, đổi mật khẩu…) thì bỏ qua lần này thay vì chờ, nên request không bao
     * giờ bị chặn vì cập nhật trạng thái online. Không đổi {@code updated_at} vì đây không phải sửa dữ liệu nghiệp vụ.
     */
    @Modifying
    @Query(value = """
            UPDATE accounts SET last_seen_at = :now
            WHERE id = (SELECT id FROM accounts
                        WHERE id = :accountId AND (last_seen_at IS NULL OR last_seen_at < :threshold)
                        FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int touchLastSeen(@Param("accountId") Long accountId, @Param("now") Instant now,
            @Param("threshold") Instant threshold);
}
