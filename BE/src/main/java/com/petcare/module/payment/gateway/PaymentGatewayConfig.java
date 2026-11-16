package com.petcare.module.payment.gateway;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Nạp {@link PaymentProperties}; bean {@code PaymentGatewayClient} chọn theo {@code app.payment.gateway} (task 127). */
@Configuration
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentGatewayConfig {
}
