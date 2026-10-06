package com.petcare.module.identity.entity;

import com.petcare.module.identity.api.ConfigValueType;
import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code system_configs} (erd §1, REF, PK chuỗi). Một tham số [CFG] kèm khoảng hợp lệ min–max (BR-QT-13);
 * giá trị lưu dạng chuỗi, kiểu theo {@code valueType}. Seed bởi {@code V2__seed_system_configs.sql}; đọc qua
 * {@code SystemConfigApi} (docs/adr/0004). Sửa giá trị (UC10) làm ở task QT sau.
 */
@Getter
@Entity
@Table(name = "system_configs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SystemConfig extends TimestampedEntity {

    @Id
    @Column(name = "key")
    private String configKey;

    @Column(name = "value", nullable = false)
    private String value;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false)
    private ConfigValueType valueType;

    @Column(name = "min_value")
    private String minValue;

    @Column(name = "max_value")
    private String maxValue;

    @Column(name = "unit")
    private String unit;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "updated_by")
    private Long updatedBy;
}
