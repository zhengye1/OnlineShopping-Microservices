package com.onlineshopping.product.repository;

import com.onlineshopping.product.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link User}.
 *
 * <p>Lookup by email backed by the {@code uk_users_email} unique index
 * (V1 migration). {@code @Repository} is technically optional on a
 * {@code JpaRepository} subtype but kept for explicit clarity and to
 * mark this as part of the persistence layer.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /** Lookup by email — used by login flow + duplicate-registration check. */
    Optional<User> findByEmail(String email);

    /** Cheap existence probe (avoids loading the full entity). */
    boolean existsByEmail(String email);
}
