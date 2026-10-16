# 🔗 Distributed URL Shortener (Bitly / TinyURL Clone)

> A production-grade, distributed URL shortening service built with **Java 21** and **Spring Boot 3.3.5**. Demonstrates modern system design patterns: coordination-free Snowflake ID generation, multi-tier caching (L1 Caffeine + L2 Redis), cross-node cache invalidation via Redis Pub/Sub, cache penetration defense, non-blocking asynchronous analytics, and Resilience4j rate limiting.

[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-DC382D?style=for-the-badge&logo=redis&logoColor=white)](https://redis.io/)
[![Flyway](https://img.shields.io/badge/Flyway-10-CC0200?style=for-the-badge&logo=flyway&logoColor=white)](https://flywaydb.org/)
[![Docker](https://img.shields.io/badge/Docker-24+-2496ED?style=for-the-badge&logo=docker&logoColor=white)](https://www.docker.com/)

---

## 📋 Table of Contents

- [Problem Statement & Scale](#-problem-statement--scale)
- [System Architecture](#-system-architecture)
- [Tech Stack](#-tech-stack)
- [Architecture Deep Dive](#-architecture-deep-dive)
  - [1. Snowflake ID Generation + Base62](#1-snowflake-id-generation--base62)
  - [2. Multi-Tier Caching (L1 Caffeine + L2 Redis)](#2-multi-tier-caching-l1-caffeine--l2-redis)
  - [3. Distributed Invalidation via Redis Pub/Sub](#3-distributed-invalidation-via-redis-pubsub)
  - [4. Cache Penetration Defense (Tombstones)](#4-cache-penetration-defense-tombstones)
  - [5. HTTP 302 Found vs 301 Moved Permanently](#5-http-302-found-vs-301-moved-permanently)
  - [6. Non-Blocking Click Tracking (@Async)](#6-non-blocking-click-tracking-async)
  - [7. Rate Limiting with Resilience4j](#7-rate-limiting-with-resilience4j)
- [Database Schema](#-database-schema)
- [Quick Start](#-quick-start)
- [API Documentation & Testing (cURL)](#-api-documentation--testing-curl)
- [Load Testing with Autocannon](#-load-testing-with-autocannon)
- [Observability & Diagnostics](#-observability--diagnostics)
- [System Design Decision Matrix](#-system-design-decision-matrix)
- [Scaling Beyond This Service](#-scaling-beyond-this-service)

---

## 🎯 Problem Statement & Scale

Design a high-throughput, latency-critical URL shortening platform that:
- Converts long URLs into compact 7-character aliases.
- Redirects short URLs to original destinations with sub-5ms latency.
- Supports custom aliases and configurable expiration dates.
- Asynchronously records click events and analytics.
- Protects downstream databases from thundering herds and cache-miss attacks.

### Capacity Estimation (Back-of-the-Envelope)

| Metric | Calculation | Value |
|---|---|---|
| **New URLs Created** | 100 Million / month | **~40 writes/sec** (avg) |
| **Read:Write Ratio** | Read-heavy system | **100 : 1** |
| **Read Traffic (Redirects)** | 40 writes/sec × 100 | **~4,000 reads/sec** (avg) |
| **Peak Read Traffic** | 5× average surge | **~20,000 reads/sec** |
| **Storage (5 Years)** | 100M × 12 × 5 = 6 Billion records × ~500 bytes | **~3 TB** |
| **Memory Working Set (80/20 Rule)** | 20% hot URLs cached in Redis (1 day) | **~35 GB** RAM |

> **Key Architectural Insight:** With a 100:1 read-to-write ratio, keeping read latency sub-5ms requires eliminating database hits on the redirect hot path through hierarchical caching.

---

## 🏗 System Architecture

### High-Level Component Diagram

```
                               ┌─────────────┐
                               │   Client    │
                               └──────┬──────┘
                                      │
                               ┌──────▼──────┐
                               │ Load Balancer│
                               └──────┬──────┘
                                      │
                 ┌────────────────────┴────────────────────┐
                 │                                         │
                 ▼                                         ▼
         ┌───────────────┐                         ┌───────────────┐
         │ Shorten API   │                         │  Redirect API │
         │ (Write Path)  │                         │  (Read Path)  │
         └───────┬───────┘                         └───────┬───────┘
                 │                                         │
         ┌───────▼────────┐                        ┌───────▼────────┐
         │ Snowflake Node │                        │  L1: Caffeine  │ (In-memory, ~0ms)
         │ (64-bit ID)    │                        └───────┬────────┘
         └───────┬────────┘                                │ (L1 Miss)
                 │ Base62 Encode                   ┌───────▼────────┐
                 ▼                                 │   L2: Redis    │ (Distributed, ~1-2ms)
         ┌────────────────┐                        └───────┬────────┘
         │ PostgreSQL 15  │                                │ (L2 Miss)
         │ (Flyway schema)│◄───────────────────────────────┘
         └────────────────┘
                 │
                 ▼
         ┌────────────────┐
         │ Async Analytics│ (@Async thread pool / Event Stream)
         └────────────────┘
```

### Request Lifecycles

#### 1. Write Path (`POST /api/v1/urls`)
1. Client submits target URL, optional custom alias, and expiration days.
2. If no custom alias is provided, the local **Snowflake Generator** creates a 64-bit ID.
3. ID is encoded into a 7-character Base62 string (e.g., `aB3x9k1`).
4. Mapping is persisted to **PostgreSQL**.
5. Both **L1 (Caffeine)** and **L2 (Redis)** caches are **pre-warmed** immediately to prevent cold-start cache misses.
6. The full short URL is returned to the client (`HTTP 201 Created`).

#### 2. Read Path (`GET /{shortCode}`)
1. Request arrives at `RedirectController`.
2. **L1 Cache (Caffeine)** checked: if present, return instantly (nanoseconds, zero network I/O).
3. **L2 Cache (Redis)** checked: if present, promote to L1 and return (~1-2ms).
4. **Database (PostgreSQL)** checked on total cache miss:
   - **Found:** Populate L2 + L1 caches and proceed.
   - **Not Found:** Cache a **Tombstone Sentinel** (`__NULL__`, 60s TTL) to halt cache penetration attacks, then throw `UrlNotFoundException` (`HTTP 404`).
5. **Asynchronous Analytics:** Background worker records click event, IP, User-Agent, and increments aggregate count without blocking user redirect.
6. Return `HTTP 302 Found` with `Location` header.

---

## 🛠 Tech Stack

| Layer | Technology | Rationale |
|---|---|---|
| **Language** | Java 21 | Modern LTS features: Java Records, pattern matching, virtual-threads-ready |
| **Framework** | Spring Boot 3.3.5 | Production-ready DI, Actuator metrics, embedded Tomcat 10 |
| **Primary Database** | PostgreSQL 15 | Relational ACID guarantees, partial indexes, proven replication |
| **L1 Local Cache** | Caffeine 3.x | Near-zero latency JVM in-memory caching with Window TinyLFU eviction |
| **L2 Distributed Cache** | Redis 7 | Shared in-memory store, string key-value TTLs, Pub/Sub channel support |
| **Schema Migration** | Flyway 10 | Version-controlled, idempotent, automated SQL schema deployments |
| **Resilience & Throttling** | Resilience4j 2.2 | In-memory token bucket rate limiting on public endpoints |
| **Testing** | Testcontainers 1.20 | Production-parity integration tests spinning up real Postgres & Redis containers |
| **API Documentation** | SpringDoc OpenAPI 2.3 | Swagger UI specification automatically generated from Java code |

---

## 🔍 Architecture Deep Dive

### 1. Snowflake ID Generation + Base62
Traditional database auto-incrementing IDs create a central bottleneck and single point of failure under horizontal scaling. We use a customized **Twitter Snowflake algorithm**:
- **41 bits:** Epoch timestamp (milliseconds) — valid for ~69 years.
- **10 bits:** Node/Machine ID (configured via `APP_SNOWFLAKE_NODE_ID`) — supports up to 1,024 worker instances.
- **12 bits:** Per-millisecond sequence counter — up to 4,096 unique IDs per millisecond per node.

The resulting 64-bit integer is converted into a **Base62** string `[0-9a-zA-Z]`:
$$62^7 \approx 3.52 \text{ Trillion unique combinations}$$
7 alphanumeric characters cleanly provide enough capacity for decades of traffic without coordination.

### 2. Multi-Tier Caching (L1 Caffeine + L2 Redis)
- **L1 (Caffeine):** Bound to the local JVM process memory. Serves viral links in nanoseconds without consuming Redis network bandwidth. Eviction policy uses **Window TinyLFU** with an explicit max size (`10,000` entries) and `expireAfterWrite=300s`.
- **L2 (Redis):** Distributed cache shared across all application replicas. Holds short code mappings with a 24-hour TTL (`86400s`), ensuring cache survival across node restarts.

### 3. Distributed Invalidation via Redis Pub/Sub
Because L1 caches live locally on each server node, updating or deactivating a URL on one instance could leave stale records in other instances.
- When an eviction or deactivation event occurs, the active node publishes an invalidation message to a Redis Pub/Sub channel (`bitly:cache:invalidate`).
- All subscriber instances receive the message and evict the corresponding key from their local Caffeine cache within milliseconds.

### 4. Cache Penetration Defense (Tombstones)
When malicious actors or bots scan random, non-existent short codes, every request causes a cache miss that hits PostgreSQL directly.
- **Defense:** If a key does not exist in the database, a tombstone sentinel value (`__NULL__`) is stored in both L1 and L2 caches with a short TTL (60 seconds).
- Subsequent repeated queries for the non-existent code hit the tombstone in cache and fail fast without touching the database.

### 5. HTTP 302 Found vs 301 Moved Permanently
- **HTTP 301 (Moved Permanently):** Browsers aggressively cache 301 redirects locally on disk. Subsequent user clicks never hit the server, completely breaking click tracking and analytics.
- **HTTP 302 (Found / Temporary Redirect):** Instructs browsers to check back with our shortener server on every visit, guaranteeing accurate click telemetry.

### 6. Non-Blocking Click Tracking (`@Async`)
The redirect endpoint is the hottest path in the system. Incrementing counters and writing analytics synchronously would add latency and create database write contention.
- Analytics recording is delegated to Spring's `@Async` thread pool.
- The 302 redirect is dispatched to the user immediately while the background thread records the click event and updates analytics.

### 7. Rate Limiting with Resilience4j
Protects the write path against spam and abuse. Configured with a token-bucket rate limiter that throttles excessive URL creation attempts per client while preserving high throughput on reads.

---

## 🗄 Database Schema

The database schema is managed via Flyway (`src/main/resources/db/migration/V1__init_schema.sql`):

```sql
-- URL Mappings Table
CREATE TABLE url_mapping (
    id              BIGINT PRIMARY KEY,              -- Snowflake 64-bit ID
    short_code      VARCHAR(10) NOT NULL UNIQUE,     -- Base62 short alias
    original_url    TEXT NOT NULL,                   -- Target destination URL
    custom_alias    BOOLEAN DEFAULT FALSE,           -- User-provided vs generated
    created_at      TIMESTAMP DEFAULT NOW(),
    expires_at      TIMESTAMP,                       -- Optional expiration
    created_by      VARCHAR(100),
    click_count     BIGINT DEFAULT 0,                -- Aggregated counter
    is_active       BOOLEAN DEFAULT TRUE             -- Soft delete flag
);

-- Indexes for performance
CREATE INDEX idx_short_code ON url_mapping(short_code);
CREATE INDEX idx_expires_at ON url_mapping(expires_at) WHERE expires_at IS NOT NULL;

-- Click Analytics Table
CREATE TABLE click_analytics (
    id              BIGSERIAL PRIMARY KEY,
    short_code      VARCHAR(10) NOT NULL,
    clicked_at      TIMESTAMP DEFAULT NOW(),
    ip_address      VARCHAR(45),
    user_agent      TEXT,
    referrer        TEXT
);

CREATE INDEX idx_click_short_code ON click_analytics(short_code);
```

---

## 🚀 Quick Start

### Prerequisites
- **JDK 21** or higher
- **Docker Desktop** (or Docker Engine)
- **Maven 3.9+** (or use the included `mvnw.cmd` / `./mvnw`)

### 1. Clone the Repository
```bash
git clone https://github.com/<your-username>/<repo-name>.git
cd <repo-name>
```

### 2. Start PostgreSQL & Redis via Docker
The repository includes a [docker-compose.yml](file:///c:/Users/Pratik/Downloads/bitly-clone/bitly-clone/docker-compose.yml) configured with healthchecks:

```bash
docker compose up -d postgres redis
```

> **Port Allocation:**
> - PostgreSQL runs on host port **`5433`** (mapped to container `5432`) to prevent conflicts with native Windows/local PostgreSQL installations.
> - Redis runs on host port **`6379`**.

### 3. Run the Spring Boot Application
Run using the Maven Wrapper:

**Windows (PowerShell):**
```powershell
.\mvnw.cmd spring-boot:run
```

**Linux / macOS (Bash):**
```bash
./mvnw spring-boot:run
```

The application will start on **`http://localhost:8082`**.

---

## 📡 API Documentation & Testing (cURL)

### Interactive Swagger UI
Explore and execute requests interactively in your browser:
👉 **[http://localhost:8082/swagger-ui.html](http://localhost:8082/swagger-ui.html)**

---

### Sample cURL Commands

#### 1. Health & Readiness Check
```bash
curl http://localhost:8082/actuator/health
```

#### 2. Shorten a URL (Auto-Generated Code)
```bash
curl -X POST http://localhost:8082/api/v1/urls \
  -H "Content-Type: application/json" \
  -d '{"originalUrl": "https://github.com/spring-projects/spring-boot"}'
```
*PowerShell syntax:*
```powershell
curl.exe -X POST http://localhost:8082/api/v1/urls `
  -H "Content-Type: application/json" `
  -d "{\"originalUrl\": \"https://github.com/spring-projects/spring-boot\"}"
```
**Response (`201 Created`):**
```json
{
  "shortUrl": "http://localhost:8082/1Zk8d2A",
  "shortCode": "1Zk8d2A",
  "originalUrl": "https://github.com/spring-projects/spring-boot",
  "createdAt": "2026-10-16T15:00:00",
  "expiresAt": null
}
```

#### 3. Shorten a URL with Custom Alias and Expiration
```bash
curl -X POST http://localhost:8082/api/v1/urls \
  -H "Content-Type: application/json" \
  -d '{"originalUrl": "https://google.com", "customAlias": "my-google", "expiresInDays": 30}'
```

#### 4. Resolve / Redirect Short URL (HTTP 302)
Inspect the HTTP redirect headers:
```bash
curl -i http://localhost:8082/my-google
```
Follow the redirect to the final website:
```bash
curl -L http://localhost:8082/my-google
```

#### 5. Retrieve Analytics & Click Statistics
```bash
curl http://localhost:8082/api/v1/urls/my-google/stats
```
**Response (`200 OK`):**
```json
{
  "shortCode": "my-google",
  "originalUrl": "https://google.com",
  "clickCount": 1,
  "createdAt": "2026-10-16T15:00:00",
  "expiresAt": "2026-11-15T15:00:00"
}
```

#### 6. Inspect Caffeine L1 Cache Diagnostics
```bash
curl http://localhost:8082/api/v1/cache/l1-stats
```
**Response (`200 OK`):**
```json
{
  "hitRate": 0.85,
  "hitCount": 1700,
  "missCount": 300,
  "loadSuccessCount": 300,
  "evictionCount": 5,
  "estimatedSize": 295
}
```

---

## ⚡ Load Testing with Autocannon

To benchmark read-path throughput and evaluate L1 vs L2 cache performance:

1. Install [autocannon](https://github.com/mcollina/autocannon) via npm:
   ```bash
   npm install -g autocannon
   ```

2. Generate a test short code first:
   ```bash
   curl -s -X POST http://localhost:8082/api/v1/urls -H "Content-Type: application/json" -d '{"originalUrl":"https://spring.io"}'
   ```

3. Run a benchmark with 100 concurrent connections for 10 seconds:
   ```bash
   autocannon -c 100 -d 10 http://localhost:8082/<your-short-code>
   ```

**Expected Results on Modern Hardware:**
- **L1 Cache Hits (Caffeine):** ~15,000 - 25,000 req/sec with p99 latency < 2ms.
- **L2 Cache Hits (Redis):** ~8,000 - 12,000 req/sec with p99 latency < 5ms.

---

## 📊 Observability & Diagnostics

This service exposes Spring Boot Actuator endpoints for Prometheus monitoring:
- **Health:** `GET http://localhost:8082/actuator/health`
- **Application Metrics:** `GET http://localhost:8082/actuator/metrics`
- **Prometheus Scrape Endpoint:** `GET http://localhost:8082/actuator/prometheus`
- **Caffeine Cache Live Stats:** `GET http://localhost:8082/api/v1/cache/l1-stats`

---

## ⚖️ System Design Decision Matrix

| Dimension | Option Chosen | Alternative Considered | Why Chosen |
|---|---|---|---|
| **ID Generation** | Snowflake + Base62 | DB Sequence / UUID | Distributed, collision-free, no DB locks, compact 7 chars |
| **Caching Layer** | L1 Caffeine + L2 Redis | Redis only | Eliminates network hop for hot links; reduces Redis pressure |
| **Cache Invalidation** | Redis Pub/Sub | Polling / Short TTL | Near-instant cross-node sync on modifications |
| **Invalid Key Attacks** | Tombstone Sentinel (60s) | Bloom Filter | Zero setup overhead; stops cache penetration at cache layer |
| **Redirect Status** | HTTP 302 Found | HTTP 301 Moved | Preserves visibility for click telemetry on every request |
| **Click Logging** | Spring `@Async` | Synchronous DB insert | Removes database write latency from redirect critical path |
| **Database** | PostgreSQL | Cassandra / DynamoDB | Strong ACID consistency, schema migration support with Flyway |
| **Testing** | Testcontainers | In-memory H2 | Tests real PostgreSQL & Redis behavior in CI environments |

*(For full, in-depth interview answers and debugging scenarios, see [interview.md](file:///c:/Users/Pratik/Downloads/bitly-clone/bitly-clone/interview.md)).*

---

## 📈 Scaling Beyond This Service

If traffic scales from 4,000 reads/sec to 1,000,000+ reads/sec:

1. **Edge Caching via Cloudflare / CDN:**
   - Cache popular 302 redirects at Edge Point of Presence (PoP) locations with a short TTL (e.g., 60 seconds) to terminate traffic before hitting application origins.
2. **Kafka Event Streaming for Analytics:**
   - Replace Spring's in-memory `@Async` thread pool with an **Apache Kafka** topic (`url-clicks`).
   - A dedicated Consumer cluster aggregates click streams and batches writes into a ClickHouse or BigQuery analytical data store.
3. **Database Sharding:**
   - Shard PostgreSQL by hashing the `short_code` across multiple read-replica clusters.
4. **Bloom Filters in Front of Cache:**
   - Deploy an in-memory Redis Bloom Filter to filter out non-existent keys before querying L2 or PostgreSQL.

---

## 📄 License
This project is open-source and available under the [MIT License](LICENSE).
