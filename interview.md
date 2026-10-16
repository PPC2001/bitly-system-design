Interview Preparation Guide
This document consolidates every design decision, trade-off, and debugging lesson from building this URL shortener — organized as Q&A so you can rehearse out loud before your interview. Each answer is written the way you should actually say it, not just a technical note.

📋 Table of Contents
System Design Fundamentals
ID Generation & Uniqueness
Caching Strategy (L1 + L2)
Database Design
API Design Decisions
Reliability & Failure Handling
Scaling the System
Code & Architecture Decisions
Testing Strategy
Build Tooling & Debugging War Stories
1. System Design Fundamentals
   Q: Walk me through how you'd design a URL shortener.
   I start with requirements and scale estimation before touching architecture. Functionally: shorten a URL, redirect it, support custom aliases and expiration, and track analytics. Non-functionally, the key insight is that this is an extremely read-heavy system — roughly 100:1 read-to-write ratio in practice, since each shortened URL gets clicked far more often than it's created. That single fact drives almost every downstream design decision: aggressive caching, read replicas, and keeping the write path simple since it's infrequent.

I estimated capacity: at 100M new URLs/month with 5-year retention, that's ~6 billion records and ~3TB of storage — not "big data" scale, but enough that I wouldn't want unindexed table scans. On the traffic side, ~40 writes/sec average but ~4,000 reads/sec average, with bursts far higher — that's squarely in "cache in front of your database" territory.

Q: What's the high-level architecture?
Client → Load Balancer → two logical services (Shorten/write path, Redirect/read path) → a two-tier cache (Caffeine in-process, then Redis distributed) → PostgreSQL as the source of truth. ID generation uses a Snowflake-style distributed generator rather than a database sequence, specifically so the write path doesn't need central coordination as we scale horizontally.

2. ID Generation & Uniqueness
   Q: How do you ensure short codes are unique across multiple server instances without a central lock?
   I use a Snowflake-style ID generator — each instance gets a unique node ID (configured via environment variable), and IDs are composed of a 41-bit timestamp, 10-bit node ID, and 12-bit per-millisecond sequence number. This guarantees global uniqueness with zero coordination between nodes, unlike a database auto-increment column, which becomes a write bottleneck and single point of contention the moment you shard the database.

The 64-bit ID is then Base62-encoded to produce the actual short code — Base62 uses [0-9][A-Z][a-z], which is case-sensitive and URL-safe without escaping, unlike Base64 which includes + and /. With 7 characters, Base62 gives ~3.5 trillion unique combinations, which comfortably covers our 6-billion-URL estimate with headroom.

Q: Why not just use a UUID?
UUIDs are globally unique but are 36 characters as a string — far too long for a "short" URL, and not easily Base62-compressible without losing the uniqueness guarantee. Snowflake IDs are compact (64 bits) specifically because they encode just enough entropy (timestamp + node + sequence) rather than full randomness, so they compress much better into a short alphanumeric code.

Q: What happens in the extremely rare case of a collision?
Collisions are astronomically unlikely by construction, since timestamp + node ID + sequence counter can't repeat under normal operation. But I still defensively check existsByShortCode() before persisting, with up to 3 retries generating a fresh ID — a cheap safety net for an event that should essentially never occur, rather than trusting the math blindly.

3. Caching Strategy (L1 + L2)
   Q: Why add an in-process (Caffeine) cache in front of Redis at all? Isn't Redis already fast enough?
   Redis is fast — typically 1-2ms — but it still requires a network round-trip. An in-process cache like Caffeine serves from local JVM heap memory in nanoseconds, with zero network overhead. For a viral short URL getting thousands of hits per second on a single instance, avoiding that network hop on every request meaningfully reduces both end-user latency and load on the shared Redis cluster. It's the classic "memory is faster than network" argument applied to caching hierarchies — the same reasoning behind CPU L1/L2/L3 cache design.

Q: What's the trade-off of adding a local cache layer?
Consistency. Redis is a single shared cache, so all instances see the same data by construction. Caffeine is per-JVM-instance — if one instance evicts or updates a key, other instances' local caches don't automatically know about it.

I solved this with Redis Pub/Sub: whenever an instance evicts a key (e.g., an admin deactivates a URL), it publishes an invalidation event on a shared channel. Every instance subscribes to that channel and clears its own local L1 entry on receipt. It's eventually consistent within the Pub/Sub propagation delay — typically single-digit milliseconds — which is an acceptable trade-off for a dataset like URL mappings that's rarely mutated after creation.

Q: Why expireAfterWrite instead of expireAfterAccess for the L1 TTL?
expireAfterAccess resets the TTL countdown on every read, which means a sufficiently popular key could theoretically never expire — permanently pinning potentially stale data in memory no matter how long ago it actually changed. expireAfterWrite guarantees a hard upper bound on staleness regardless of access frequency, which is the safer default when correctness matters more than squeezing out maximum hit rate.

Q: How do you prevent the L1 cache from growing unbounded and causing memory pressure?
Caffeine.maximumSize() bounds the cache using Caffeine's Window TinyLFU eviction policy, which in published benchmarks outperforms simple LRU for hit-rate under skewed access patterns — which is exactly the pattern a URL shortener has, since a small number of URLs get a disproportionate share of traffic. Once the bound is hit, least-valuable entries are evicted automatically; I don't manage memory manually.

Q: How would you verify the cache is actually helping in production?
I exposed Caffeine's built-in stats (recordStats()) via Micrometer, bound to /actuator/metrics, tracking hit rate, eviction count, and average load time. In a real production rollout, I'd graph hit rate over time in Grafana and alert if it drops below an expected threshold — a sudden hit-rate collapse usually signals either a cache sizing regression or a genuine shift in traffic pattern, like a long tail of unique URLs replacing what used to be a small set of hot ones.

Q: Why cache-aside instead of write-through?
Cache-aside means cache failures never block the write path to the database — Redis going down degrades read latency, not write correctness. Write-through would couple every write's success to cache availability, which is unacceptable for what's supposed to be the more reliable half of the system. The small trade-off is a cold-start penalty on the very first read after a write, which I mitigate by proactively warming both cache tiers immediately after a URL is created.

Q: What is cache penetration, and how did you defend against it?
Cache penetration is when requests for non-existent keys bypass the cache every time (since there's nothing to cache) and hit the database directly on every single request — a classic vector for abuse, whether malicious scanning or just a buggy client retrying a typo'd short code in a loop.

My fix: when a lookup misses at both cache tiers and the database, I cache a short-lived tombstone sentinel value in both L1 and L2 for 60 seconds. Subsequent requests for that same invalid code get rejected right at the cache layer without ever touching Postgres again until the tombstone expires.

4. Database Design
   Q: Why PostgreSQL over a NoSQL store for this?
   The access pattern is a simple key-value lookup (short_code → original_url), which NoSQL stores handle well too — but I chose Postgres because: (1) the dataset has strong consistency requirements (a short code must map to exactly one URL, never two), (2) I need transactional guarantees when creating a mapping alongside incrementing counters, and (3) analytics queries benefit from SQL's flexibility for ad-hoc reporting. At the scale we estimated (~3TB, ~6B rows), a properly indexed Postgres setup with read replicas handles this comfortably — I'd only reach for NoSQL if I needed multi-region active-active writes, which isn't a stated requirement here.

Q: Walk me through your schema design choices.
Two tables: url_mapping for the core short-code-to-URL relationship, and click_analytics as a separate append-only table for click events. I deliberately kept these separate rather than storing click history inline, because url_mapping is read on the hot path (every redirect) while click_analytics is write-heavy and read rarely (only when someone checks stats) — mixing them would mean every analytics insert competes for locks/cache space with the latency-critical redirect path.

I indexed short_code (the primary lookup key) and conditionally indexed expires_at only where it's non-null, since most rows won't have an expiration and a partial index keeps the index smaller and faster to maintain.

5. API Design Decisions
   Q: Why HTTP 302 instead of 301 for the redirect?
   301 (Permanent Redirect) gets cached by the browser forever — after the first click, the browser never asks our server again, which means we silently lose all analytics visibility after one hit per user. 302 (Found/Temporary) forces the browser back to our server on every single click. Since click analytics is a core product feature for a URL shortener, not an afterthought, 302 is the correct choice even though it means every click costs us a server round-trip. This is a genuine trade-off, not a free lunch — I'm explicitly prioritizing observability over marginal client-side performance.

Q: Why validate URLs with a regex instead of a full URI parser?
A lightweight regex check at the API boundary catches obviously malformed input cheaply and gives fast, clear validation error messages. I wouldn't rely on regex alone for security-critical validation (e.g., SSRF protection) — in a production system I'd add a stricter allow-list/deny-list check server-side (blocking internal IP ranges, localhost, etc.) before ever attempting to store or redirect to a user-submitted URL, since URL shorteners are a known vector for SSRF and phishing abuse.

6. Reliability & Failure Handling
   Q: What happens if Redis goes down entirely?
   Because I used the cache-aside pattern, Redis is not in the critical path for correctness — only for performance. If Redis is unavailable, reads fall through directly to Postgres: degraded latency, not an outage. I'd pair this in production with a circuit breaker (I included Resilience4j in the stack for exactly this) so that Redis timeouts fail fast rather than hanging every request waiting on a dead connection pool.

Q: How do you prevent someone from brute-force scanning for valid short codes?
Multiple layers: rate limiting at the API gateway/load balancer level (not implemented in this reference build, but it's where it belongs), and the tombstone caching strategy described earlier, which means repeated requests for invalid codes get rejected by the cache layer instead of hammering Postgres. I'd also log and alert on abnormally high 404 rates from a single IP as a signal of active scanning.

Q: Why is click tracking asynchronous?
The redirect endpoint is the hottest path in the entire system given our 100:1 read:write ratio. Writing analytics synchronously — incrementing a counter and inserting an analytics row — would add unnecessary latency to every single redirect, which is exactly the request we care most about keeping fast. I used Spring's @Async to fire-and-forget the analytics write in a background thread, so the 302 response returns to the user immediately regardless of how long the analytics write takes. At larger scale, I'd replace this with publishing to Kafka and processing analytics in a separate consumer service entirely, decoupling it from the web tier's thread pool altogether.

7. Scaling the System
   Q: How would you scale this to a billion URLs and tens of thousands of requests per second?
   A few concrete changes, in priority order:

Shard PostgreSQL by hashing the short code, so no single database instance holds the entire dataset or absorbs all write traffic.
Add read replicas per shard, since reads vastly outnumber writes — replicas absorb the bulk of redirect traffic without touching the primary.
Move analytics off the synchronous write path entirely — stream click events to Kafka, batch-process into a column store like ClickHouse, rather than writing directly to Postgres even asynchronously.
Redis Cluster/Sentinel instead of a single Redis instance, for both capacity and failover.
CDN-edge redirects (e.g., Cloudflare Workers) for globally distributed low-latency redirects, with regional read replicas backing them.
The common thread is: identify what's on the hot path (reads, redirects) and make that path as cheap and distributed as possible, while being more relaxed about the write and analytics paths, which can tolerate more latency and complexity.

Q: How do you scale ID generation across multiple app instances without coordination?
This is exactly what the Snowflake design solves — each instance is assigned a unique node ID (e.g., via Kubernetes pod ordinal or an environment variable at deploy time), and IDs are generated locally with no network call to a central authority. This is a deliberate trade-off: I give up perfectly sequential IDs (Snowflake IDs are only roughly time-ordered) in exchange for horizontal scalability with zero coordination overhead.

8. Code & Architecture Decisions
   Q: Why did you use Java records for DTOs instead of Lombok, when you used Lombok everywhere else?
   Java 21 records and Lombok solve the same boilerplate problem, but records are a language-level feature with compiler-enforced immutability, while Lombok is annotation-processor-based code generation. For DTOs — pure, immutable data carriers crossing the API boundary — records are the more modern, dependency-free, idiomatic choice in Java 17+.

I reserved Lombok for JPA entities and Spring-managed beans where: entities genuinely need mutable setters (JPA requires a no-arg constructor and setters to support lazy-loading proxies), and constructor injection (@RequiredArgsConstructor) and logging (@Slf4j) boilerplate repeats across many classes. This is a deliberate architectural decision based on what each layer actually needs — not inconsistency for its own sake.

Q: I noticed you used @EqualsAndHashCode(of = "id") instead of Lombok's default on your JPA entities. Why?
Generating equals/hashCode from every field on a JPA entity is a well-known bug source — it breaks in subtle ways with lazy-loaded Hibernate proxies (which may not have all fields populated) and with mutable fields that change after the entity is put into a HashSet or used as a map key. Scoping equality to just the primary key is the standard, safe pattern for entities, since the ID is the one field guaranteed to be stable and meaningful for identity comparison.

Q: Why separate UrlCacheService out from UrlShortenerServiceImpl instead of handling caching inline?
Single Responsibility — UrlShortenerServiceImpl should only know about shortening/resolving business logic, not the internal mechanics of how many cache tiers exist or how invalidation propagates across instances. By extracting UrlCacheService, I can change the caching strategy entirely (add a third tier, swap Redis for something else, change invalidation mechanism) without touching the business logic at all. It also makes the caching logic independently unit-testable, which I did — testing L1 hits, L2 fallback-and-promotion, and full-miss behavior in isolation without needing a real Redis or database.

9. Testing Strategy
   Q: How do you test a service with external dependencies like Postgres and Redis?
   I avoid mocking the database or cache in integration tests, because that only proves my mocks behave the way I expect — not that the real system actually works end-to-end. Instead, I use Testcontainers to spin up ephemeral, real Postgres and Redis instances scoped to the test JVM's lifecycle. This gives genuine confidence that JPA mappings, Flyway migrations, and Redis serialization all work correctly against real infrastructure, while remaining fully reproducible in CI with zero manual setup — Testcontainers handles the container lifecycle automatically via Docker.

For the pure caching logic specifically, though, I used plain Mockito unit tests with an in-memory Caffeine instance and a mocked RedisTemplate — no need for real infrastructure to prove that an L1 hit short-circuits before ever touching Redis, for example. That's a pure logic test, not an integration concern.

Q: What's the difference in what you're actually proving with a unit test versus an integration test here?
A unit test (e.g., UrlCacheServiceTest) proves the logic is correct in isolation — given a mocked Redis returning a specific value, does the promotion-to-L1 behavior work as designed? An integration test (e.g., UrlShortenerServiceIntegrationTest against Testcontainers) proves the wiring is correct — does Spring actually connect to a real Postgres, do the Flyway migrations actually apply cleanly, does the real RedisTemplate serialize/deserialize correctly? Both are necessary; they catch different categories of bugs.

10. Build Tooling & Debugging War Stories
    These are less "textbook system design" and more "proof you can debug a real production-style problem methodically" — genuinely valuable to bring up if asked about challenges you faced.

Q: Tell me about a non-trivial bug you had to debug recently.
While setting this project up, I hit a Maven failure that looked completely unrelated to my actual code — an internal Guice dependency injection error inside Maven's own bootstrap process (No implementation for ArtifactHandlerManager was bound). The error pointed at Maven's internal classes, not mine, which was a strong signal the problem was environmental, not code-level.

I debugged it by isolating variables one at a time rather than guessing at the application layer first: first confirmed which JDK Maven itself was running on (mvn -version), which revealed I was on a brand-new JDK release (25) that had shipped before Maven's bundled dependency injection tooling (which relies on bytecode generation via ASM) had added support for that class file version. Separately, I also discovered my pom.xml's Spring Boot parent version had accidentally been pinned to an early 4.x release instead of a stable 3.x line, which was compounding the instability during Maven's POM resolution.

The fix was two-fold: pin the JDK to an LTS version (21) that matched what the project actually targeted, and pin the Spring Boot parent to a known-stable 3.x version. The broader lesson — and what I'd apply going forward — is that build tooling should be pinned and reproducible per project (via Maven Wrapper, Toolchains, or a documented .sdkmanrc), rather than relying on whatever happens to be globally installed on a given machine. That class of "works on my machine" bug is entirely preventable with the right tooling discipline.

Q: How do you approach debugging an error message that doesn't seem to point at the real cause?
I treat confusing or seemingly-unrelated errors as a signal to widen my search before narrowing it — rather than immediately diving into my own code, I check: is this actually coming from my code, or from a framework/tool's internals? In the Maven/Guice case above, the stack trace was entirely composed of Maven-internal classes (DefaultPlexusContainer, DefaultArtifactFactory), which told me immediately this was a tooling problem, not an application bug, before I'd even read a single line of my own source.

I also learned to distrust console output formatting in certain shells — I had a case where PowerShell's 2>&1 | tee redirection made a completely harmless JVM warning (about Mockito's inline mock maker) look like a fatal NativeCommandError. The actual signal of truth was checking $LASTEXITCODE directly, not trusting how the terminal rendered the output. It's a good reminder that in CI pipelines especially, you should always check exit codes programmatically rather than eyeballing log output, since log formatting can misrepresent success/failure.

Q: Have you dealt with IDE vs. terminal build inconsistencies?
Yes — IntelliJ maintains its own independent JDK configuration (Project SDK, Module SDK, and separately a Maven Importer JDK), which is entirely decoupled from the system JAVA_HOME or terminal PATH. I had a case where fixing the JDK version in my terminal didn't fix what IntelliJ was actually compiling against, because the Maven Importer JDK setting had drifted separately. The durable fix for a team — beyond manually syncing IDE settings — is Maven Toolchains, which makes the build explicitly require a registered JDK version and fail loudly if it's not available, removing the ambiguity entirely regardless of what any individual developer's IDE or shell happens to be configured with.

🎯 Closing Statement for Interviews
If asked to summarize the project in one or two sentences:

I built a URL shortener focused on the aspects that actually matter at scale — a two-tier caching hierarchy with cross-instance cache invalidation, a coordination-free distributed ID generator, and explicit handling of edge cases like cache penetration — rather than just a CRUD app that happens to shorten URLs. Every design choice, from the 302 redirect to the cache-aside pattern, was made by first asking what the read:write ratio and failure modes actually demand, not by defaulting to the most common pattern.

📎 Quick Reference: Design Decision Summary Table
Decision	Choice	Key Reason
ID generation	Snowflake + Base62	No coordination needed across instances
Cache pattern	Cache-aside, two-tier (Caffeine + Redis)	Cache failures don't block writes; L1 avoids network hop for hot keys
Cache invalidation	Redis Pub/Sub broadcast	Keeps per-instance L1 caches consistent
L1 eviction policy	expireAfterWrite, bounded size	Hard staleness bound; prevents unbounded memory growth
Cache penetration defense	Tombstone caching (60s TTL)	Prevents repeated DB hits for invalid/scanned codes
Redirect status code	HTTP 302	Preserves analytics visibility on every click
Database	PostgreSQL	Strong consistency, transactional guarantees, mature replication
Click tracking	Async (@Async)	Keeps redirect hot-path latency low
DTOs	Java records	Immutable, language-level, no annotation processing needed
Entities/Services	Lombok (@Slf4j, @RequiredArgsConstructor, etc.)	Reduces boilerplate where mutability/DI is genuinely needed
Testing	Testcontainers (integration) + Mockito (unit)	Real infra for wiring correctness; mocks for pure logic correctness
Build reproducibility	Pinned JDK 21, Spring Boot 3.3.5, Maven Wrapper	Prevents environment-drift bugs across machines/CI