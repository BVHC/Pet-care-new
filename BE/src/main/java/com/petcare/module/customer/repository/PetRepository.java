package com.petcare.module.customer.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.customer.api.Species;
import com.petcare.module.customer.entity.Pet;

import jakarta.persistence.LockModeType;

/**
 * Thú cưng (docs/adr/0041). {@code PESSIMISTIC_WRITE} trên PostgreSQL sinh {@code FOR NO KEY UPDATE},
 * {@code PESSIMISTIC_READ} sinh {@code FOR SHARE}. Câu native giữ điều kiện cố định (không {@code :x IS NULL OR …})
 * để dùng index.
 */
public interface PetRepository extends JpaRepository<Pet, Long> {

    /** Cột của {@code PetQueryApi.PetSummary}, trừ cân nặng hiện tại (đọc riêng theo lô). */
    record PetRow(Long id, Long customerId, String name, Species species, LocalDate birthDate, LocalDate deceasedOn) {}

    /**
     * Thú theo lô ({@code PetQueryApi.findPets}): một câu SQL, JPQL constructor nên không nạp entity vào persistence
     * context của bên gọi (bên gọi có thể khóa dòng thú sau đó trong cùng transaction). Bên gọi không truyền tập rỗng.
     */
    @Query("""
            SELECT new com.petcare.module.customer.repository.PetRepository$PetRow(
                p.id, p.customerId, p.name, p.species, p.birthDate, p.deceasedOn)
            FROM Pet p WHERE p.id IN :ids
            """)
    List<PetRow> findRowsByIds(@Param("ids") Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Pet p WHERE p.id = :id")
    Optional<Pet> findByIdForUpdate(@Param("id") Long id);

    /** Khóa chia sẻ cho appointment / visit / boarding trước khi chèn dòng trỏ tới thú ({@code PetQueryApi.findPetForShare}). */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT p FROM Pet p WHERE p.id = :id")
    Optional<Pet> findByIdForShare(@Param("id") Long id);

    /** Chủ hiện tại, không nạp entity vào persistence context (bước đọc trước khóa, docs/adr/0029 mục 4). */
    @Query("SELECT p.customerId FROM Pet p WHERE p.id = :id")
    Optional<Long> findCustomerIdById(@Param("id") Long id);

    @Query(value = "SELECT * FROM pets WHERE customer_id = :customerId ORDER BY id", nativeQuery = true)
    List<Pet> findByCustomer(@Param("customerId") Long customerId);

    @Query(value = "SELECT * FROM pets WHERE customer_id IN (:customerIds) ORDER BY customer_id, id",
            nativeQuery = true)
    List<Pet> findByCustomers(@Param("customerIds") List<Long> customerIds);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM pets WHERE customer_id = :customerId)", nativeQuery = true)
    boolean existsByCustomer(@Param("customerId") Long customerId);

    /** Nghi trùng (BR-KH-02): thú khác của cùng chủ, cùng loài, tên trùng không phân biệt hoa thường. */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM pets
                           WHERE customer_id = :customerId AND species = :species AND lower(name) = lower(:name)
                             AND id <> :excludeId)
            """, nativeQuery = true)
    boolean existsDuplicateName(@Param("customerId") Long customerId, @Param("species") String species,
            @Param("name") String name, @Param("excludeId") Long excludeId);
}
