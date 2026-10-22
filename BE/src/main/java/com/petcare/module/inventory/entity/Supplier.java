package com.petcare.module.inventory.entity;

import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code suppliers} (erd §9, REF). Nhà cung cấp dùng chung toàn chuỗi (BR-KO-02). Đã có phiếu nhập thì không xóa
 * được, chỉ {@code is_active = false} (ngừng hợp tác) — không chọn được ở phiếu nhập mới.
 */
@Getter
@Entity
@Table(name = "suppliers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Supplier extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "phone", nullable = false)
    private String phone;

    @Column(name = "address")
    private String address;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    public static Supplier create(String name, String phone, String address) {
        Supplier supplier = new Supplier();
        supplier.name = name;
        supplier.phone = phone;
        supplier.address = address;
        supplier.active = true;
        return supplier;
    }

    /** Nhận <b>giá trị cuối</b> của mọi trường sửa được; service đã quyết giữ / xóa / đổi. */
    public void update(String newName, String newPhone, String newAddress, boolean newActive) {
        this.name = newName;
        this.phone = newPhone;
        this.address = newAddress;
        this.active = newActive;
    }
}
