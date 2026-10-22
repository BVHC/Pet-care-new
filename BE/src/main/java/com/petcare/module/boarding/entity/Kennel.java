package com.petcare.module.boarding.entity;

import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code kennels} (erd §7, ROOT, SM #7). Service kiểm {@code KennelTransitionHandler} và giữ khóa trước khi gọi
 * các method đổi trạng thái ở đây.
 */
@Getter
@Entity
@Table(name = "kennels")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Kennel extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "kennel_type_id", nullable = false, updatable = false)
    private Long kennelTypeId;

    @Column(name = "code", nullable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private KennelStatus status;

    @Column(name = "note")
    private String note;

    /** Chuồng#1: — → AVAILABLE. */
    public static Kennel create(Long branchId, Long kennelTypeId, String code, String note) {
        Kennel kennel = new Kennel();
        kennel.branchId = branchId;
        kennel.kennelTypeId = kennelTypeId;
        kennel.code = code;
        kennel.note = note;
        kennel.status = KennelStatus.AVAILABLE;
        return kennel;
    }

    public void changeCode(String code) {
        this.code = code;
    }

    public void changeNote(String note) {
        this.note = note;
    }

    /** Chuồng#4. */
    public void startMaintenance() {
        this.status = KennelStatus.MAINTENANCE;
    }

    /** Chuồng#5. */
    public void endMaintenance() {
        this.status = KennelStatus.AVAILABLE;
    }
}
