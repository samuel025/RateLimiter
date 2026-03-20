##### BACKEND ENGINEERING SPECIFICATION

# Rate-Limited API Gateway

### Plain Spring Boot — Virtual Threads + RestTemplate

```
Version 1.1 • March 2026 • For Backend Developer
```
```
Project name Rate-Limited API Gateway
```
Stack (^) Java 21, Spring Boot 3.2+, PostgreSQL 15, Redis 7
HTTP client RestTemplate (blocking) + Java 21 Virtual Threads
Thread model (^) Virtual Threads via Project Loom (spring.threads.virtual.enabled=true)
Methodology Plain Spring Boot — no Spring Cloud Gateway, no WebFlux
Deliverable Runnable JAR + Docker Compose setup
Spec version (^) 1.1 — March 2026

## 1. Project Overview

This document specifies the complete backend implementation of a Rate-Limited API Gateway built in
plain Spring Boot. The gateway intercepts all incoming HTTP requests, validates API keys, enforces
configurable per-key rate limits using a Redis token bucket, proxies valid requests to a configured
upstream service using RestTemplate, and logs usage asynchronously to PostgreSQL.

There is no Spring Cloud Gateway dependency and no reactive WebFlux code. Concurrency is
handled by Java 21 Virtual Threads (Project Loom), which allow RestTemplate’s ordinary blocking calls
to scale to thousands of simultaneous in-flight proxy requests without exhausting OS threads. This is
enabled with a single configuration flag.

#### 1.1 What the system does

- Receives every HTTP request on /**
- Validates the X-Api-Key request header against a Redis-cached key store
- Checks the key’s token bucket in Redis using an atomic Lua script
- If both checks pass: forwards the request to the upstream URL using RestTemplate, returns the
    upstream response to the caller unchanged
- Publishes a UsageLogEvent after the response is returned, written to PostgreSQL on a
    separate async thread


- Exposes /admin/** endpoints for creating clients, issuing keys, revoking keys, and querying
    usage stats

#### 1.2 What the system does NOT do

- It is not a load balancer — one upstream service only
- It does not terminate TLS — run behind nginx or a cloud load balancer in production
- It does not support multiple routes or path-based routing
- It has no user-facing UI

## 2. System Architecture

#### 2.1 High-level component map

Five layers execute on every inbound request. The first two (auth and rate limit) can short-circuit the
chain early. The proxy layer is the only one that contacts the upstream. The log layer is always async
and off the critical path.

```
Layer Component Responsibility
1 — Auth AuthInterceptor
ApiKeyService
```
```
Extracts X-Api-Key header, hashes it with SHA-256, looks up
the ApiKey object in Redis (5 min TTL), falls back to
PostgreSQL on miss. Returns 401 if key is missing, invalid,
expired, or revoked.
2 — Rate
limit
```
```
RateLimitInterceptor
RateLimiterService
```
```
Runs a Lua script against Redis to atomically check-and-
decrement the key’s token bucket. Returns 429 with Retry-After
header if the bucket is empty.
3 — Proxy GatewayController
ProxyService
```
```
Catch-all @RequestMapping("/**") receives the request.
ProxyService copies method, path, query string, and headers,
calls RestTemplate.exchange() to the upstream, returns the
response body and status unchanged.
4 — Log UsageLogService (@Async
listener)
```
```
After the response is returned to the caller, GatewayController
publishes a UsageLogEvent. An @Async @EventListener
writes the row to PostgreSQL. The caller never waits for this.
5 — Admin AdminController^ REST API for managing clients and keys. Protected by HTTP
Basic Auth. Separate from the gateway path entirely.
```
#### 2.2 Why Virtual Threads instead of WebFlux

WebFlux achieves high concurrency through a non-blocking event loop, but requires all code to be
written in reactive style: Mono, Flux, flatMap chains. This is a steep learning curve and makes
debugging significantly harder.

Java 21 Virtual Threads (Project Loom) achieve the same concurrency goal while allowing completely
ordinary blocking code. When a virtual thread blocks on I/O — such as waiting for the upstream to
respond — the JVM unmounts it from the OS carrier thread and parks it. The OS thread is immediately


free to run another virtual thread. This means thousands of simultaneous proxy requests without any
thread pool tuning.

This is enabled with one configuration line. There is no WebFlux dependency in this project.

#### 2.3 Infrastructure dependencies

- PostgreSQL 15+ — primary datastore for clients, keys, and usage logs
- Redis 7+ — API key cache (hash objects, 5 min TTL) + token bucket state (hash objects, 1 hr
    TTL)
- Upstream service — any HTTP service; URL configured via environment variable
- Docker Compose — provided to spin up PostgreSQL and Redis locally for development

#### 2.4 Project package structure

```
com.gateway
├── controller/
│ ├── GatewayController.java # catch-all /** handler
│ └── AdminController.java # /admin/** management endpoints
├── filter/
│ ├── AuthInterceptor.java # HandlerInterceptor: validates API key
│ └── RateLimitInterceptor.java # HandlerInterceptor: token bucket check
├── service/
│ ├── ApiKeyService.java # key lookup, hashing, Redis cache
│ ├── RateLimiterService.java # Lua script execution against Redis
│ ├── ProxyService.java # RestTemplate forwarding logic
│ └── UsageLogService.java # @Async event listener, DB write
├── entity/
│ ├── ApiClient.java
│ ├── ApiKey.java
│ └── UsageLog.java
├── repository/
│ ├── ApiClientRepository.java
│ ├── ApiKeyRepository.java
│ └── UsageLogRepository.java
├── event/
│ └── UsageLogEvent.java
└── config/
├── RestTemplateConfig.java
├── RedisConfig.java
├── AsyncConfig.java
└── InterceptorConfig.java
```
## 3. Data Model

#### 3.1 ApiClient

Represents an organisation or developer account. One client can have multiple API keys.


```
Field Type Constraints Notes
id UUID PK Generated UUID, not sequential
```
name VARCHAR(100) (^) NOT NULL Organisation or developer name
email VARCHAR(255) UNIQUE
NOT NULL
Contact email
active BOOLEAN (^) NOT NULL false = all keys for this client stop working
immediately
createdAt TIMESTAMP (^) NOT NULL UTC, set on insert

#### 3.2 ApiKey

Represents a single API key issued to a client. The raw key is shown exactly once at issuance and
never stored. Only the SHA-256 hex hash is persisted.

```
Field Type Constraints Notes
```
id UUID PK (^)
clientId UUID FK →
ApiClient
Owning client
keyHash VARCHAR(64) (^) UNIQUE
NOT NULL
SHA-256 hex of the raw key. Never store the
raw key.
prefix VARCHAR(8) (^) NOT NULL First 8 chars of the raw key, for display only
rateLimit INT NOT NULL Maximum requests per minute allowed for this
key
tier ENUM NOT NULL BASIC | PRO | ENTERPRISE
active BOOLEAN (^) NOT NULL false = key is revoked. Cache must be
invalidated on revocation.
expiresAt TIMESTAMP NULLABLE null = never expires
createdAt TIMESTAMP (^) NOT NULL UTC

#### 3.3 UsageLog

One row per successfully proxied request. Partition by month from the start — this table grows fast.

```
Field Type Constraints Notes
```
id BIGSERIAL (^) PK Sequential for performance on this table
apiKeyId UUID FK → ApiKey (^)
endpoint VARCHAR(500) NOT NULL Request URI path
method VARCHAR(10) NOT NULL GET, POST, DELETE, etc.
statusCode INT (^) NOT NULL Upstream response HTTP status code


responseTimeMs INT (^) NOT NULL Elapsed time from request received to
response returned to caller
ipAddress VARCHAR(45) (^) NULLABLE Caller’s IP address. Supports IPv6.
timestamp TIMESTAMP NOT NULL UTC. Use as the partition key.

## 4. API Endpoints

#### 4.1 Gateway (proxy) endpoints

All requests to /** are intercepted and proxied. The catch-all handler covers every method and every
path. No fixed routes need to be defined.

```
Method Path Description
```
**ANY** /** (^) Catch-all. Validates API key, checks rate limit, proxies to
upstream. Returns upstream response as-is including
status code, headers, and body.

#### 4.2 Admin endpoints

Protected by HTTP Basic Auth (admin:admin in development). All request and response bodies are
JSON. The prefix field is returned instead of keyHash anywhere a key is listed.

```
Method Path Description
```
**POST** (^) /admin/clients (^) Create a new API client. Body: { name, email }
**GET** /admin/clients (^) List all clients with active status
**GET** /admin/clients/{id} (^) Get a single client by ID
**PATCH** /admin/clients/{id}/deactivate (^) Deactivate a client. All their keys stop working
immediately.
**POST** (^) /admin/clients/{id}/keys (^) Issue a new key. Body: { rateLimit, tier, expiresAt? }.
Returns the raw key once in the response — it
cannot be retrieved again.
**GET** /admin/clients/{id}/keys (^) List all keys for a client (prefix and metadata only,
never hash)
**DELETE** /admin/keys/{id} (^) Revoke a key immediately. Must also invalidate the
Redis cache entry.
**GET** (^) /admin/keys/{id}/usage (^) Usage stats for one key. Query param:
period=24h|7d|30d
**GET** (^) /admin/clients/{id}/usage (^) Aggregate usage across all keys for a client. Same
period param.


#### 4.3 Standard error response shape

All error responses use this JSON structure:

##### {

```
"error": "string", // human-readable message
"code": "string", // machine-readable code, see table below
"timestamp": "ISO-8601" // UTC timestamp of the error
}
```
```
HTTP
status
```
```
code field Condition
```
**401** (^) MISSING_API_KEY X-Api-Key header is absent from the request
**401** INVALID_API_KEY (^) Key not found in DB, revoked (active=false), or past expiresAt
**429** (^) RATE_LIMIT_EXCEEDED Token bucket is empty. Include Retry-After header (seconds until next
token refill).
**502** UPSTREAM_ERROR (^) Upstream returned 5xx or connection was refused
**504** (^) UPSTREAM_TIMEOUT Upstream did not respond within the 10-second read timeout

## 5. Component Specifications

#### 5.1 AuthInterceptor

Implements HandlerInterceptor. Must be registered before RateLimitInterceptor in InterceptorConfig.
Runs on every request except /admin/**.

- Extract the value of the X-Api-Key header
- If absent: write 401 MISSING_API_KEY JSON response, return false to stop the chain
- Compute SHA-256 hex hash of the raw key value
- Call ApiKeyService.resolve(hash) to get the ApiKey entity
- If null, inactive, or past expiresAt: write 401 INVALID_API_KEY JSON response, return false
- Store the resolved entity on the request: request.setAttribute("apiKey", apiKey)
- Return true to continue the interceptor chain

#### 5.2 RateLimitInterceptor

Implements HandlerInterceptor. Runs after AuthInterceptor. Reads the ApiKey set as a request
attribute.

- Retrieve ApiKey from request.getAttribute("apiKey")
- Call RateLimiterService.tryConsume(apiKey)
- If false: write 429 RATE_LIMIT_EXCEEDED JSON response, set Retry-After header (seconds
    until next token), return false


- Return true to continue

#### 5.3 ApiKeyService

- resolve(hash): look up ApiKey in Redis first at key "apikey:{hash}". On cache miss, query
    PostgreSQL by keyHash field, store result in Redis with 5-minute TTL. Return null if not found.
- isValid(hash): returns resolve(hash) != null && apiKey.isActive() && (apiKey.getExpiresAt() ==
    null || apiKey.getExpiresAt().isAfter(Instant.now()))
- invalidateCache(hash): delete the Redis key "apikey:{hash}" immediately. Called by
    AdminController when a key is revoked so the 5-minute cache does not delay revocation taking
    effect.
- generateKey(): returns a cryptographically random 32-byte value encoded as Base64URL. This
    is the raw key shown to the user once.
- hash(rawKey): SHA-256 hex encoding. Always hash before any DB or Redis operation.

#### 5.4 RateLimiterService

All rate limit state is stored in Redis. The token bucket for each key lives at Redis key "rl:{apiKeyId}" as
a hash with two fields: tokens (float) and last_refill (epoch milliseconds as string).

The check-and-decrement operation must be atomic. Implement it as a Lua script loaded at startup with
RedisScript<Long> and executed with RedisTemplate.execute(). The script returns 1 (allowed) or 0
(rate limited).

Lua script logic

- Read tokens and last_refill from the Redis hash
- elapsed = (now - last_refill) / 1000.0 [convert ms to seconds]
- refill_rate = rateLimit / 60.0 [tokens per second]
- refilled = math.min(capacity, tokens + elapsed * refill_rate)
- If refilled >= 1.0: write (refilled - 1) and now back to the hash, set TTL to 3600 seconds, return 1
- Else: return 0

capacity equals rateLimit (a full bucket = 1 full minute of quota). The Lua script must handle the case
where the hash does not yet exist — treat it as a full bucket.

#### 5.5 ProxyService

Uses a RestTemplate bean configured with a 5-second connect timeout and 10-second read timeout.
The bean is built in the constructor from an injected RestTemplateBuilder.

- Build the upstream URI: upstreamUrl + request.getRequestURI() + (queryString != null? "?" +
    queryString : "")
- Copy all request headers into an HttpHeaders object, skipping the Host header


- Read the request body as byte[]: request.getInputStream().readAllBytes()
- Call restTemplate.exchange(uri, method, new HttpEntity<>(body, headers), byte[].class)
- Return the ResponseEntity<byte[]> directly to GatewayController
- On HttpStatusCodeException (upstream 4xx/5xx): return a ResponseEntity with the same
    status, headers, and body from the exception — pass it through unchanged
- On ResourceAccessException where cause is SocketTimeoutException: return 504
- On ResourceAccessException for connection refused: return 502

#### 5.6 GatewayController

A single @RestController with one method. The method is annotated @RequestMapping("/**") with no
method restriction so it matches every HTTP verb.

```
@RestController
public class GatewayController {
```
```
private final ProxyService proxyService;
private final ApplicationEventPublisher eventPublisher;
```
```
@RequestMapping("/**")
public ResponseEntity<byte[]> proxy(HttpServletRequest request) throws
IOException {
long start = System.currentTimeMillis();
ResponseEntity<byte[]> response = proxyService.forward(request);
```
```
// Publish usage log event (handled async, does not block response)
ApiKey apiKey = (ApiKey) request.getAttribute("apiKey");
eventPublisher.publishEvent(new UsageLogEvent(
apiKey.getId(),
request.getRequestURI(),
request.getMethod(),
response.getStatusCode().value(),
(int)(System.currentTimeMillis() - start),
request.getRemoteAddr()
));
```
```
return response;
}
}
```
#### 5.7 UsageLogService

The @EventListener method must be annotated @Async so it runs on a separate thread pool and does
not delay the response to the caller.

- Listen for UsageLogEvent using @EventListener @Async
- Map the event fields to a new UsageLog entity and call UsageLogRepository.save()
- Wrap the entire method body in try-catch. Any exception must be logged and swallowed — it
    must never propagate to the event publishing thread.


The thread pool for @Async is defined in AsyncConfig with corePoolSize=4, maxPoolSize=8,
queueCapacity=500. Name the executor "usageLogExecutor" and set it as the default in
@EnableAsync.

#### 5.8 AdminController

A standard @RestController at /admin/**. Protected by Spring Security HTTP Basic Auth. The admin
username and password are set in application.yml for development. All list responses return arrays;
single-item responses return objects.

- POST /admin/clients: validate body, save ApiClient, return 201 with the created entity
- POST /admin/clients/{id}/keys: generate raw key, hash it, save ApiKey with hash and prefix.
    Return 201 with { id, prefix, rawKey, rateLimit, tier, expiresAt }. This is the only time rawKey
    appears in any response.
- DELETE /admin/keys/{id}: set active=false, save, then call
    ApiKeyService.invalidateCache(key.getKeyHash()) to immediately remove the Redis cache
    entry
- GET /admin/keys/{id}/usage: query UsageLog by apiKeyId and timestamp >= now minus the
    period. Return { totalRequests, successCount, errorCount, rateLimitedCount }

## 6. Spring Boot Features Used

This table maps each Spring Boot feature to where and why it is used. Refer to this when structuring
imports and configuration classes.

```
Feature Used in Purpose
HandlerInterceptor AuthInterceptor
RateLimitInterceptor
```
```
Intercepts requests before the controller. preHandle()
returns false to short-circuit the chain and write an
error response directly.
WebMvcConfigurer InterceptorConfig Registers interceptors in order. Auth must be
registered before RateLimit.
```
RestTemplate ProxyService (^) Blocking HTTP client for forwarding requests to the
upstream. Configured with connect and read
timeouts.
RestTemplateBuilder RestTemplateConfig (^) Spring Boot auto-configured builder. Injected into
ProxyService to create the RestTemplate instance.
Virtual Threads (Loom) application.yml (^) spring.threads.virtual.enabled=true tells Tomcat to
use a new virtual thread per request. No code
changes needed.
RedisTemplate RateLimiterService
ApiKeyService
Low-level Redis operations. Used to execute Lua
scripts (rate limiter) and get/set/delete key objects
(auth cache).
RedisScript<Long> RateLimiterService (^) Wraps the Lua script for the token bucket. Loaded
once at startup, executed atomically per request.


@Cacheable ApiKeyService (^) Optional: can be added to the resolve() method
backed by Redis for simpler cache management.
Manual RedisTemplate approach also acceptable.
Spring Data JPA All repositories (^) Standard JpaRepository for ApiClient, ApiKey,
UsageLog CRUD.
ApplicationEventPublisher GatewayController (^) Publishes UsageLogEvent after the response is
ready to return.
@EventListener + @Async UsageLogService Receives UsageLogEvent on a separate thread pool.
Writes UsageLog row without blocking the response.
@EnableAsync AsyncConfig (^) Enables async method execution. Defines the thread
pool executor for UsageLogService.
Spring Security AdminController (^) HTTP Basic Auth protecting /admin/**. The gateway
path /** bypasses security and is authenticated by
AuthInterceptor instead.
Actuator + Micrometer All (^) Expose /actuator/health and /actuator/metrics. Add
custom counters for total requests, rate-limited
requests, and proxy latency.
Flyway DB schema (^) Manages database migrations. Create a V1__init.sql
with all three table definitions.

## 7. Configuration

#### 7.1 application.yml

```
server:
port: 8080
```
```
spring:
threads:
virtual:
enabled: true # enables Project Loom virtual threads on Tomcat
```
```
datasource:
url: ${DB_URL:jdbc:postgresql://localhost:5432/gateway}
username: ${DB_USER:gateway}
password: ${DB_PASS:gateway}
jpa:
hibernate:
ddl-auto: validate # Flyway manages the schema; JPA just validates
show-sql: false
```
```
data:
redis:
host: ${REDIS_HOST:localhost}
port: ${REDIS_PORT:6379}
```
```
security:
user:
name: admin # HTTP Basic credentials for /admin/**
password: admin
```

```
gateway:
upstream:
url: ${UPSTREAM_URL:http://localhost:9090}
timeout:
connect-ms: 5000
read-ms: 10000
```
```
management:
endpoints:
web:
exposure:
include: health,metrics,prometheus
```
#### 7.2 Environment variables

```
Variable Default Description
```
UPSTREAM_URL [http://localhost:9090](http://localhost:9090) (^) Full base URL of the service
being proxied
DB_URL jdbc:postgresql://localhost:5432/gateway (^) JDBC connection URL for
PostgreSQL
DB_USER gateway^ PostgreSQL username
DB_PASS gateway (^) PostgreSQL password
REDIS_HOST localhost^ Redis hostname
REDIS_PORT (^6379) Redis port

## 8. Maven Dependencies (pom.xml)

Use Spring Boot 3.2+ parent. Set Java version to 21. There is no WebFlux dependency —
RestTemplate is included in the web starter.

```
<properties>
<java.version>21</java.version>
</properties>
```
```
<dependencies>
<!-- Web (includes RestTemplate and Spring MVC) -->
<dependency>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-starter-web</artifactId>
</dependency>
```
```
<!-- Spring Data JPA -->
<dependency>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-starter-data-jpa</artifactId>
```

```
</dependency>
```
```
<!-- Redis -->
<dependency>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```
```
<!-- Spring Security (admin endpoint protection) -->
<dependency>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-starter-security</artifactId>
</dependency>
```
```
<!-- Actuator + Prometheus metrics -->
<dependency>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
<groupId>io.micrometer</groupId>
<artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```
```
<!-- PostgreSQL driver -->
<dependency>
<groupId>org.postgresql</groupId>
<artifactId>postgresql</artifactId>
<scope>runtime</scope>
</dependency>
```
```
<!-- Flyway (DB migrations) -->
<dependency>
<groupId>org.flywaydb</groupId>
<artifactId>flyway-core</artifactId>
</dependency>
```
```
<!-- Lombok -->
<dependency>
<groupId>org.projectlombok</groupId>
<artifactId>lombok</artifactId>
<optional>true</optional>
</dependency>
```
```
<!-- Test -->
<dependency>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-starter-test</artifactId>
<scope>test</scope>
</dependency>
</dependencies>
```
## 9. Recommended Build Order


Follow this sequence. At every step, the application should start and the completed behaviour should
be verifiable with curl.

```
# What to build Done when...
1 Docker Compose + Flyway
schema
```
```
docker compose up starts PostgreSQL and Redis. Application starts
and Flyway creates all three tables.
```
```
2 Entities + Repositories ApiClient, ApiKey, and UsageLog can be saved and queried in an
integration test.
3 AdminController POST /admin/clients creates a client. POST /admin/clients/{id}/keys
returns a response with a rawKey field.
4 ProxyService +
GatewayController (no auth)
```
```
Any request to /** is forwarded to the upstream and the upstream
response is returned. Test with a simple GET.
```
```
5 ApiKeyService +
AuthInterceptor
```
```
Requests with no X-Api-Key get 401 MISSING_API_KEY. Requests
with a valid key are proxied. Requests with a bad key get 401
INVALID_API_KEY.
6 RateLimiterService +
RateLimitInterceptor
```
```
After rateLimit requests in a minute, subsequent requests return 429
with a Retry-After header. After the interval, requests are accepted
again.
7 UsageLogService (@Async) Every successful proxy creates a UsageLog row in PostgreSQL. The
response time for the proxy call is not measurably increased.
8 Cache invalidation on
revoke
```
```
Calling DELETE /admin/keys/{id} causes the very next request with that
key to return 401 immediately, not after the 5-minute cache TTL
expires.
```
## 10. Stretch Goals

Implement these after all acceptance criteria pass. Each is a self-contained addition.

- Per-endpoint rate limits: change the Redis bucket key from "rl:{keyId}" to "rl:{keyId}:{path}".
    Each endpoint gets its own bucket, allowing different limits per route for the same key.
- Key rotation: issue a new key while the old one remains valid for a configurable grace period
    (e.g. 24 hours). Both keys work simultaneously during the overlap window.
- Usage rollups: a @Scheduled job runs every hour and aggregates UsageLog rows into an
    hourly_usage table (keyId, hour, requestCount, errorCount). Speeds up /usage queries on large
    datasets significantly.
- Circuit breaker: wrap the RestTemplate call in Resilience4j @CircuitBreaker. If the upstream
    fails repeatedly, the circuit opens and subsequent requests get an immediate 503 instead of
    waiting for the timeout.
- Custom Micrometer metrics: use MeterRegistry to record gateway.requests.total (tagged by
    tier), gateway.rate_limited.total, and gateway.proxy.latency as a Timer. These appear in
    /actuator/prometheus automatically.
- Virtual thread pinning check: run the application under load with -Djdk.tracePinnedThreads=full.
    Identify any synchronized blocks in dependencies that pin virtual threads to OS threads and
    document findings.


## 11. Acceptance Criteria

The implementation is complete when all of the following can be verified with curl or a REST client
against a running instance:

```
# Acceptance criterion
```
```
1 A request with no X-Api-Key header returns 401 with code MISSING_API_KEY.
2 A request with an unrecognised or revoked key returns 401 with code INVALID_API_KEY.
3 A request with a valid, active key is proxied to the upstream and the upstream’s response body,
status code, and headers are returned unchanged to the caller.
4 After rateLimit requests within a 60-second window, the next request returns 429 with code
RATE_LIMIT_EXCEEDED and a Retry-After header.
5 After the Retry-After period elapses, requests with the same key are accepted again.
6 Revoking a key via DELETE /admin/keys/{id} causes the very next request with that key to return 401
— not after a cache delay.
7 Every successful proxy creates one UsageLog row in PostgreSQL within 2 seconds of the response
being returned.
8 The response latency of a proxied request is not measurably higher than a direct call to the upstream
(confirming async logging is not blocking).
9 POST /admin/clients/{id}/keys returns a rawKey field exactly once. A subsequent GET
/admin/clients/{id}/keys does not show the raw key, only the prefix.
10 The application starts cleanly from docker compose up + java -jar without manual database setup.
```
