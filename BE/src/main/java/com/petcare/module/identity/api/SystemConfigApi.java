package com.petcare.module.identity.api;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * Owner: identity (QT) · BE-1. Đọc tham số [CFG] (BR-QT-13), key dạng {@code otp.ttl_minutes}.
 * Giá trị mới chỉ áp dụng cho giao dịch tạo sau: caller tự chốt giá trị vào bản ghi khi cần.
 */
public interface SystemConfigApi {

    int getInt(String key);

    BigDecimal getDecimal(String key);

    boolean getBool(String key);

    LocalTime getTime(String key);
}
