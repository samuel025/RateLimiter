package com.rate.ratelimiter.services.impl;

import com.rate.ratelimiter.services.RateLimiterService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

@Service
public class RateLimiterServiceImpl implements RateLimiterService {

    private static final Logger logger = LoggerFactory.getLogger(RateLimiterServiceImpl.class);
    private static final RedisScript<String> TOKEN_BUCKET_SCRIPT = buildScript();
    private final StringRedisTemplate redisTemplate;

    public RateLimiterServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public RateLimitDecision checkAndConsume(UUID apiKeyId, int limitPerMinute, Instant now) {
        if (apiKeyId == null) {
            throw new IllegalArgumentException("apiKeyId is required");
        }
        if (limitPerMinute <= 0) {
            throw new IllegalArgumentException("limitPerMinute must be > 0");
        }
        if (now == null) {
            now = Instant.now();
        }

        double refillRatePerSecond = (double) limitPerMinute / 60.0;

        long maxTokens = limitPerMinute;

        long nowMicros = now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;


        String tokensKey = "tokenbucket:tokens:" + apiKeyId;
        String refillKey = "tokenbucket:refill:" + apiKeyId;


        long ttlSeconds = 120;

        try {
            String result = redisTemplate.execute(
                TOKEN_BUCKET_SCRIPT,
                List.of(tokensKey, refillKey),      
                String.valueOf(maxTokens),           
                String.valueOf(refillRatePerSecond), 
                String.valueOf(nowMicros),           
                String.valueOf(ttlSeconds)          
            );

            if (result == null || result.isBlank() || !result.contains(":")) {
                throw new IllegalStateException("Redis script returned invalid result");
            }

            String[] parts = result.split(":", 2);
            boolean allowed = "1".equals(parts[0]);
            int remaining = (int) Math.max(0, Double.parseDouble(parts[1]));

            long retryAfterSeconds = allowed ? 0L : (long) Math.ceil(1.0 / refillRatePerSecond);
            Instant resetAt = now.plusSeconds(retryAfterSeconds);

            if (allowed) {
                return RateLimitDecision.allowed(limitPerMinute, remaining, resetAt);
            }
            return RateLimitDecision.denied(limitPerMinute, resetAt, retryAfterSeconds);

        } catch (RuntimeException ex) {
            logger.error("Redis token bucket check failed for apiKeyId={}. Failing open.", apiKeyId, ex);
            return RateLimitDecision.allowed(limitPerMinute, limitPerMinute - 1, now.plusSeconds(60));
        }
    }

    private static RedisScript<String> buildScript() {
        String lua = """
            local tokens_key  = KEYS[1]
            local refill_key  = KEYS[2]
            local max_tokens  = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local now_micros  = tonumber(ARGV[3])
            local ttl         = tonumber(ARGV[4])
                        
            local last_refill = tonumber(redis.call('GET', refill_key) or now_micros)
                        
            local current_tokens = tonumber(redis.call('GET', tokens_key) or max_tokens)
                        
            local elapsed_micros = now_micros - last_refill
                        
            local elapsed_seconds = elapsed_micros / 1000000
                        
            local tokens_to_add = elapsed_seconds * refill_rate
        
                        
            local new_tokens = math.min(max_tokens, current_tokens + tokens_to_add)
                        
            if new_tokens < 1 then
                redis.call('SET', tokens_key, new_tokens, 'EX', ttl)
                redis.call('SET', refill_key, now_micros,  'EX', ttl)
                return '0:' .. new_tokens
                -- Denied. Not even 1 full token available.
                -- Still update both keys so refill continues from now.
            end
                        
            new_tokens = new_tokens - 1
                        
            redis.call('SET', tokens_key, new_tokens, 'EX', ttl)
            redis.call('SET', refill_key, now_micros,  'EX', ttl)
                        
            return '1:' .. new_tokens
            """;

        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(lua);
        script.setResultType(String.class);
        return script;
    }
}