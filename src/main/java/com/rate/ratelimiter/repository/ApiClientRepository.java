
package com.rate.ratelimiter.repository;

import com.rate.ratelimiter.entity.ApiClient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApiClientRepository extends JpaRepository<ApiClient, UUID> {
    Optional<ApiClient> findById(UUID id);
    Optional<ApiClient> findByName(String name);
    Optional<ApiClient> findByContactEmailIgnoreCase(String contactEmail);
}