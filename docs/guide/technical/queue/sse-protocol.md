# Admin queue SSE protocol

Paths are relative to the configured context path. The stream carries invalidation hints only; current job data is always fetched from the REST API.

## Transport and authorization

- `GET /api/admin/queue/v1/events` returns `200`, `Content-Type: text/event-stream`, `Cache-Control: no-cache`, and `X-Accel-Buffering: no`. There is no application `Connection: close` header or transport-specific admission check. Spring MVC `SseEmitter` supports servlet transports including TLS, HTTP/2, and proxies.
- Use the same-origin authenticated Yona session and current site-admin predicate as HTML/REST. Anonymous or expired sessions receive JSON `401`; non-admin or pending-2FA sessions receive JSON `403`. GET is read-only and does not require CSRF. Do not enable cross-origin access.
- Caps are 32 registered streams per application instance, 2 per principal, and 2 per session. Excess receives JSON `429` (`RATE_LIMITED`) and `Retry-After: 1` before an emitter is created. A closing stream retains its registration until both a completion/error callback and any in-flight sender have finished; a blocked container cannot create an unbounded backlog by releasing quota early.
- One shared node poller checks generation and distinct current principals/sessions once per second. No stream owns a database connection, transaction, or polling thread. An independent JDK one-second deadline requests closure even if pool acquisition blocks the poller; new streams receive JSON `503` while that overdue lookup remains in flight.
- Revocation, user disabling, session invalidation, or idle session expiry stops subsequent application sends and requests `complete()` within two seconds. Every send also rejects authority samples older than two seconds. An already-started write may finish; the application does not retract bytes already handed to the container. The stream does not extend session idle expiry.
- Each emitter has a five-minute timeout. EventSource reconnects through authentication and receives a new `reset`. Reconnection requests refresh the session's last-access time, so leaving the administration page open can keep the session alive despite its idle timeout.
- Do not add `text/event-stream` to `server.compression.mime-types` or enable proxy buffering/compression for this route. Spring Boot excludes it from its default compression list. The application does not inspect or replace connector settings.

## Event grammar

Frames are UTF-8 SSE, terminated by a blank line. Data is compact JSON. There are no job payloads, filenames, errors, logs, URLs, credentials, or result bytes in events.

The first event after connection or reconnection is always `reset`:

```text
event: reset
data: {"reason":"connect"}

```

`reason` is `connect`, `reconnect`, or `buffer-overflow`. The client refetches its current filtered page/detail through REST.

A committed queue mutation changes the shared DB generation and emits an invalidation:

```text
event: changed
data: {"generation":"3021"}

```

`generation` is a signed 64-bit monotonic sequence represented as a decimal string. Gaps are normal. Healthy streams normally receive committed changes within two seconds. Neither event content nor arrival order replaces a durable REST snapshot.

A healthy stream receives a comment within fifteen seconds, even during continuous changes. Its cadence tracks the last heartbeat, not the last invalidation; a due comment can accompany a `changed` or `reset` frame without consuming another pending-event slot:

```text
: heartbeat

```

Heartbeats are not job updates and do not refresh the displayed list or extend session validity.

## Bounded output and lifecycle

- Each stream has one in-flight send and at most one pending event. Multiple pending `changed` invalidations coalesce into one `reset` with reason `buffer-overflow`. A heartbeat never displaces an invalidation or the initial reset.
- The shared poller only queues events. Blocking `SseEmitter.send()` runs on a separate executor: one virtual thread per task when `spring.threads.virtual.enabled=true`, otherwise a fixed pool of 32 platform threads. No worker/transaction thread writes to SSE.
- A send still in flight after two seconds marks that stream terminal, discards pending output, and requests `complete()` without blocking the shared poller. Completion runs independently of the sender pool because it can wait for the emitter's write lock; retained registration quotas bound those completion tasks to 32 per node as well. Other streams and queue workers remain independent of the blocked reader.
- Actual socket close and release of blocked container writes depend on the servlet container's socket write timeout. OS file-descriptor disappearance, TCP shutdown timing, and container-private async counters are not application guarantees.
- `SmartLifecycle.stop()` stops admissions, requests completion for all streams, and shuts down executors before web-server graceful shutdown. Healthy streams finish before server teardown; blocked writes retain the same container-timeout limitation.

## Replay and client convergence

`Last-Event-ID` is accepted but does not request replay. No SSE `id` is required, and no process-local event history is retained. Every new connection begins with `reset` and re-reads durable state.

1. Fetch the current page/detail from REST.
2. Open one same-origin EventSource.
3. On `reset` or `changed`, refetch; cancel superseded requests and retain the newest snapshot.
4. On disconnect, show the last snapshot as stale and let EventSource reconnect. If it becomes `CLOSED` after a server error such as `429`, retry after fifteen seconds.
5. Close EventSource on page exit. Do not add a fallback polling loop.

## Verification

Run Kotlin MVC async integration coverage with virtual threads on and off, including heartbeat delivery while committed changes continue throughout the heartbeat interval. The live two-node acceptance runner also checks initial/reconnect resets, idle heartbeat, generation convergence, JSON quota errors, shared DB polling, role/session revocation, a nonreading raw socket isolated from a healthy stream and worker, and shutdown EOF. Its private pressure filter writes bounded legal comments through normal servlet output; it has no container-internal dependencies.
