package com.reservation.users;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository
        extends JpaRepository<User, Long> {

    // lower(email) совпадает с выражением уникального индекса users_email_lower_uk.
    @Query("select count(u) > 0 from User u where lower(u.email) = :email")
    boolean existsByNormalizedEmail(@Param("email") String normalizedEmail);

    @Query("select u from User u where lower(u.email) = :email")
    Optional<User> findByNormalizedEmail(
            @Param("email") String normalizedEmail
    );
}
