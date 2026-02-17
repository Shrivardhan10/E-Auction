package com.eauction.repository;

import com.eauction.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    Optional<User> findByEmailAndRole(String email, String role);

    List<User> findByRole(String role);

    List<User> findByIsActive(Boolean isActive);

    long countByRole(String role);
}
