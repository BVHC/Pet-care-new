package com.petcare.module.pet.repository;

import com.petcare.module.pet.entity.Pet;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PetRepository extends JpaRepository<Pet, UUID> {

    List<Pet> findByOwnerId(UUID ownerId);

    Page<Pet> findByOwnerId(UUID ownerId, Pageable pageable);
}
