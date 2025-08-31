# Velocity Hardening Plan

Goal: Make authentication and networking resilient to abusive spikes and remote auth/server flakiness, preventing Netty I/O starvation and cascading disconnects.

Status
- Fixed: Decoupled JDK HttpClient from Netty event loops (commit 82e4451a). This removes a starvation vector under load.

Phase 1 — Short-Term (Low risk, high impact)
- Shared HttpClient: Reuse a singleton `HttpClient` across requests instead of per-login creation. Stop closing per-request clients in `InitialLoginSessionHandler`.
  - Builder: `.connectTimeout(5s)`, `.version(HTTP_1_1)` (Mojang supports 1.1), dedicated executor (see below).
- Dedicated HTTP executor: Create a bounded `ThreadPoolExecutor` for auth/HTTP with named threads (not Netty event loops, not commonPool).
  - Example: core=max(4, 2xCPU), max=32, queue=1024, `CallerRunsPolicy` to shed under extreme load.
  - Shutdown executor on proxy shutdown.
- Per-request timeouts: Set `HttpRequest.Builder.timeout(5s)` and wrap `CompletableFuture` with an overall guard (e.g., 6–8s) to ensure completion.
- Cancellation on disconnect: Store auth future on the connection and `cancel(true)` if the player disconnects; short-circuit continuations when `mcConnection.isClosed()`.
- Concurrency bulkhead: Limit max in-flight auth requests via `Semaphore` (configurable, default 256). Deny fast with user-friendly message if saturated to avoid pile-ups.
- Gentle retry with jitter: For transient I/O (connect timeout, EOF), retry up to 1–2 times with exponential backoff (total <1.5s additional) — no retry on 4xx/5xx.
- Observability: Log auth latency buckets, error categorization, and bulkhead saturation; expose counters/gauges for dashboards.

Phase 2 — Resilience and Backpressure
- Small positive cache: Optional, short-lived (e.g., 30s) positive cache of hasJoined results keyed by `(username, serverId, ip)` to smooth retries if the response is reused in a tight window. Guard correctness and disable by default.
- Circuit breaker: If remote auth failure rate > X% over Y seconds, open breaker for a brief window (e.g., 10–30s) to fast-fail with a clear message instead of overwhelming threads with doomed requests.
- Backpressure in login pipeline: When bulkhead is full or breaker is open, fail fast instead of queueing work on event loops.

Phase 3 — Threading Hygiene and Plugin Safety
- Event loop watchdog: Log a WARN stack sample if any Netty worker task runs > 200ms to surface blocking code.
- Offload heavy work: Ensure CPU/IO heavy tasks (JSON parsing, crypto beyond current usage) run off event loops.
- Plugin guidance: Provide helper executors and short docs for plugin authors; detect common patterns of performing blocking I/O on event loops and log advisories.

Phase 4 — Netty and Queue Robustness
- Chat/command queue guardrails: When backend connection drops, drop or buffer with small TTL instead of throwing `IllegalStateException` and spamming logs.
- Tune Idle/keepalive: Review `IdleStateHandler` and timeouts to reduce noisy disconnect storms while keeping detection snappy.

Configuration (new/updated)
- `http.maxAuthConcurrency` (int, default 256): Max concurrent hasJoined requests.
- `http.connectTimeoutMs` (int, default 5000) and `http.requestTimeoutMs` (int, default 5000–8000).
- `http.maxQueue` (int, default 1024): Queue size for the HTTP executor.
- `auth.retry.count` (0–2, default 1) and `auth.retry.initialBackoffMs` (100–200ms).
- `auth.circuitBreaker.enabled` (bool), `failureThresholdPct` (e.g., 50), `windowSeconds` (e.g., 20), `openSeconds` (e.g., 15).
- `loginRatelimit` (already exists): Revisit defaults and document guidance for public servers.

Code Changes (high level)
- `VelocityServer`:
  - Hold a singleton `HttpClient` and a dedicated `ExecutorService` for HTTP.
  - Expose `getHttpClient()` and `shutdownHttpExecutor()`; wire shutdown in server stop.
- `InitialLoginSessionHandler`:
  - Use shared client; remove per-request `AutoCloseable` close.
  - Apply per-request timeout; capture and cancel future on disconnect.
  - Add concurrency bulkhead and lightweight retry with jitter.
  - Optional: consult short-lived positive cache.
- `ConnectionManager`:
  - No longer responsible for per-request client creation; keep networking concerns isolated from HTTP.
- `VelocityConfiguration`:
  - Add new config options and validation with sane bounds.

Observability
- Metrics: counters for auth attempts, successes, failures (by category), retries, timeouts, breaker state. Histogram for auth latency.
- Logs: Single-line structured entries on failures including username (hashed), IP (redacted), durations, and error type.

Validation Plan
- Load test: Simulate 500–1000 concurrent login attempts; verify stable Netty I/O and bounded auth concurrency.
- Chaos: Inject 50% auth connect timeouts and 5xx responses; verify retries limited, breaker engages, and the proxy remains responsive.
- Regression: Ensure normal login latency remains low; verify no resource leaks on shutdown.

Rollout and Risk
- Gate new behaviors behind config flags; defaults conservative.
- Staged rollout: enable shared client + timeouts first; then bulkhead; then (optionally) caching and breaker.
- Rollback: Config toggles to disable each feature independently.

Success Criteria
- No Netty starvation under auth spikes; no mass disconnects related to auth HTTP overload.
- Auth error spikes do not degrade unrelated proxy networking.
- Clear, actionable telemetry for operators when Mojang services degrade.

