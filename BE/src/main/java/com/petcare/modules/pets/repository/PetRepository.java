package com.petcare.modules.pets.repository;

import com.petcare.modules.pets.entity.Pet;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PetRepository extends JpaRepository<Pet, Long> {

    // Find all pets by owner
    List<Pet> findByOwnerId(Long ownerId);

    // Find all pets by owner with pagination
    Page<Pet> findByOwnerId(Long ownerId, Pageable pageable);

    // Find pet by ID and owner (for authorization)
    Optional<Pet> findByIdAndOwnerId(Long id, Long ownerId);

    // Find all pets by species
    List<Pet> findBySpecies(String species);

    // Count pets by owner
    long countByOwnerId(Long ownerId);

    // Check if pet exists for owner
    boolean existsByIdAndOwnerId(Long id, Long ownerId);

    // Search pets by name (case-insensitive)
    @Query("SELECT p FROM Pet p WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :name, '%'))")
    Page<Pet> searchByName(@Param("name") String name, Pageable pageable);
}
