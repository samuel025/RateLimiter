package com.rate.ratelimiter.repository;

import com.rate.ratelimiter.entity.UsageLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UsageLogRepository
	extends JpaRepository<UsageLog, UUID>, JpaSpecificationExecutor<UsageLog> {}
