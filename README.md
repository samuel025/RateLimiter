# RateLimiter API Gateway

## Overview

`RateLimiter` is a Spring Boot API Gateway that sits in front of an upstream application and enforces per-client API key authentication and request rate limiting.

The gateway provides:

- API key validation
- Per-key rate limiting
- Request proxying to an upstream service
- Usage logging for auditing and analytics
- Admin endpoints for key lifecycle management

This project is built with a classic Spring MVC stack and uses virtual threads for scalable request handling while keeping a straightforward blocking programming model.

---

## Architecture Summary

### Request flow

1. Client sends request to gateway (`/gateway/**`) with `X-API-Key`.
2. `AuthInterceptor` validates API key and client status.
3. `RateLimitInterceptor` checks and consumes request quota.
4. `GatewayController` forwards request through `ProxyService`.
5. `ProxyService` calls upstream API using `RestTemplate`.
6. `UsageLogService` stores request metadata and outcome.
7. Response is returned to the client.

### Main components

- `AuthInterceptor`  
  Validates API key and sets authenticated context on request attributes.

- `RateLimitInterceptor`  
  Enforces per-key request quotas and sets rate-limit headers.

- `ApiKeyService`  
  API key lifecycle and validation logic (find, validate, create, revoke, update).

- `RateLimiterService`  
  Core check-and-consume rate limit engine.

- `ProxyService`  
  Forwards request to upstream and returns normalized response.

- `UsageLogService`  
  Persists request logs for monitoring and reporting.

- `GatewayController`  
  Catch-all gateway endpoint for proxied traffic.

- `AdminController`  
  Key management endpoints for administrative operations.

---

## Tech Stack

- Java 21
- Spring Boot (MVC)
- Spring Data JPA
- PostgreSQL
- Maven
- Virtual Threads (`spring.threads.virtual.enabled=true`)
- RestTemplate for outbound upstream calls

---

## Project Structure

Typical package layout:

- `com.rate.ratelimiter.config`
- `com.rate.ratelimiter.controllers`
- `com.rate.ratelimiter.entity`
- `com.rate.ratelimiter.interceptors`
- `com.rate.ratelimiter.repository`
- `com.rate.ratelimiter.services`
- `com.rate.ratelimiter.services.impl`

---

## Configuration

Set these in `src/main/resources/application.properties`:

- application name
- server port
- virtual threads
- database connection
- JPA settings
- upstream base URL

