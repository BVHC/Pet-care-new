package com.petcare.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Bật {@code @Async}. Mỗi chỗ dùng phải ghi rõ executor của mình ({@code @Async("<bean>")}) và có ADR, vì quy ước
 * chung cấm xử lý bất đồng bộ trong chuỗi hệ quả nghiệp vụ (convention 07 §7.2). Hiện chỉ có kích gửi email luồng HIGH
 * sau commit (docs/adr/0154).
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
