package com.rate.ratelimiter.repository;

import com.rate.ratelimiter.entity.UsageLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsageLogRepository extends JpaRepository<UsageLog, UUID> {}
