package com.petcare.module.customer.entity;

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
 * Bảng {@code addresses} (erd §3, PART của Customer). Sổ địa chỉ của khách: tối đa {@code address.max_per_customer}
 * [CFG], đúng 1 địa chỉ mặc định (BR-TK-18; partial unique {@code uq_addresses_default_per_customer}, không deferrable).
 * {@code customerId} để dạng id, không map {@code @ManyToOne}: service luôn lọc theo chủ trong câu SQL.
 * <p>
 * Cột {@code is_default} map vào field {@code defaultAddress} (cùng cách {@code Account.locked} ↔ {@code is_locked}):
 * mapper đổi lại thành {@code isDefault} của hợp đồng. Xóa cứng được (BR-TK-18, docs/adr/0028): không bảng nào có FK
 * trỏ tới {@code addresses}.
 */
@Getter
@Entity
@Table(name = "addresses")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Address extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "receiver_name", nullable = false)
    private String receiverName;

    @Column(name = "receiver_phone", nullable = false)
    private String receiverPhone;

    @Column(name = "address_line", nullable = false)
    private String addressLine;

    @Column(name = "ward")
    private String ward;

    @Column(name = "province", nullable = false)
    private String province;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAddress;

    /**
     * Địa chỉ mới của {@code customerId}. Caller đã khóa hồ sơ, kiểm giới hạn [CFG] và — khi {@code makeDefault} — đã gỡ
     * cờ mặc định cũ bằng câu UPDATE chạy ngay (docs/adr/0028).
     */
    public static Address create(Long customerId, String receiverName, String receiverPhone, String addressLine,
            String ward, String province, boolean makeDefault) {
        Address address = new Address();
        address.customerId = customerId;
        address.receiverName = receiverName;
        address.receiverPhone = receiverPhone;
        address.addressLine = addressLine;
        address.ward = ward;
        address.province = province;
        address.defaultAddress = makeDefault;
        return address;
    }

    /** Nhận giá trị cuối của mọi trường sửa được; cờ mặc định chỉ đổi qua {@link #markDefault()}. */
    public void update(String newReceiverName, String newReceiverPhone, String newAddressLine, String newWard,
            String newProvince) {
        this.receiverName = newReceiverName;
        this.receiverPhone = newReceiverPhone;
        this.addressLine = newAddressLine;
        this.ward = newWard;
        this.province = newProvince;
    }

    /** Đặt làm mặc định. Caller đã gỡ cờ của địa chỉ mặc định cũ bằng câu UPDATE chạy trước (index không deferrable). */
    public void markDefault() {
        this.defaultAddress = true;
    }
}
