package com.petcare.module.customer.entity;

import java.time.LocalDate;

import com.petcare.module.customer.api.Species;
import com.petcare.platform.exception.BusinessRuleViolationException;
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
 * Bảng {@code pets} (erd §3, ROOT). Thú cưng thuộc đúng một chủ hiện tại ({@code customerId}, BR-KH-08). Đã mất
 * ({@code deceasedOn != null}) là chỉ đọc, không hoàn tác (BR-KH-05): mọi method ghi ném BR-KH-05. Quy tắc cần dữ liệu của
 * module khác (khóa loài BR-KH-03, xóa BR-KH-06, Visit mở BR-KH-05) kiểm ở service, không ở entity.
 * <p>
 * Caller giữ khóa dòng {@code pets} ({@code FOR NO KEY UPDATE}) từ trước khi đọc entity (docs/adr/0041).
 */
@Getter
@Entity
@Table(name = "pets")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Pet extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "species", nullable = false)
    private Species species;

    @Enumerated(EnumType.STRING)
    @Column(name = "sex", nullable = false)
    private PetSex sex;

    @Column(name = "breed")
    private String breed;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "birth_date_estimated", nullable = false)
    private boolean birthDateEstimated;

    @Column(name = "color")
    private String color;

    @Column(name = "is_neutered")
    private Boolean neutered;

    @Column(name = "photo_url")
    private String photoUrl;

    @Column(name = "deceased_on")
    private LocalDate deceasedOn;

    /** Thú mới của {@code customerId}; caller đã kiểm ngày sinh không ở tương lai (BR-KH-02). */
    public static Pet create(Long customerId, String name, Species species, PetSex sex, String breed,
            LocalDate birthDate, boolean birthDateEstimated, String color, Boolean neutered, String photoUrl) {
        Pet pet = new Pet();
        pet.customerId = customerId;
        pet.name = name;
        pet.species = species;
        pet.sex = sex;
        pet.breed = breed;
        pet.birthDate = birthDate;
        pet.birthDateEstimated = birthDateEstimated;
        pet.color = color;
        pet.neutered = neutered;
        pet.photoUrl = photoUrl;
        return pet;
    }

    public boolean isDeceased() {
        return deceasedOn != null;
    }

    /** Nhận <b>giá trị cuối</b> của mọi trường sửa được; caller đã kiểm khóa loài (BR-KH-03) và ngày sinh (BR-KH-02). */
    public void update(String newName, Species newSpecies, PetSex newSex, String newBreed, LocalDate newBirthDate,
            boolean newBirthDateEstimated, String newColor, Boolean newNeutered, String newPhotoUrl) {
        requireAlive();
        this.name = newName;
        this.species = newSpecies;
        this.sex = newSex;
        this.breed = newBreed;
        this.birthDate = newBirthDate;
        this.birthDateEstimated = newBirthDateEstimated;
        this.color = newColor;
        this.neutered = newNeutered;
        this.photoUrl = newPhotoUrl;
    }

    /** BR-KH-05: không hoàn tác. Caller đã kiểm Visit mở / đang lưu trú và ngày mất hợp lệ. */
    public void markDeceased(LocalDate date) {
        requireAlive();
        this.deceasedOn = date;
    }

    /** BR-KH-08: chỉ đổi chủ hiện tại; Order cũ giữ chủ cũ. */
    public void transferTo(Long newCustomerId) {
        requireAlive();
        this.customerId = newCustomerId;
    }

    /** BR-KH-05: thú đã mất chỉ đọc. */
    public void requireAlive() {
        if (isDeceased()) {
            throw new BusinessRuleViolationException("BR-KH-05", "Thú cưng đã mất, hồ sơ chỉ đọc");
        }
    }
}
