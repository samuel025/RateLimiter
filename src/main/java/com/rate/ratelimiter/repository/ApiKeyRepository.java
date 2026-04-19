package com.rate.ratelimiter.repository;

import com.rate.ratelimiter.entity.ApiKey;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyHash(String keyHash);

    @EntityGraph(attributePaths = "client")
    Optional<ApiKey> findWithClientByKeyHash(String keyHash);
    
}
