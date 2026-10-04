package com.petcare.module.identity.api;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * Owner: identity (QT) · BE-1. Đọc tham số [CFG] (BR-QT-13) từ cache trong bộ nhớ (docs/adr/0004).
 * Giá trị mới chỉ áp dụng cho giao dịch tạo sau: caller tự chốt giá trị vào bản ghi khi cần
 * (ví dụ {@code sessions.expires_at}, {@code otp_tokens.expires_at}).
 * Gọi getter không đúng {@link ConfigKey#type()} là lỗi lập trình → {@link IllegalArgumentException}.
 */
public interface SystemConfigApi {

    int getInt(ConfigKey key);

    BigDecimal getDecimal(ConfigKey key);

    boolean getBool(ConfigKey key);

    LocalTime getTime(ConfigKey key);
}
