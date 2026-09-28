# Admin queue SSE protocol

**Contract:** Accepted SPEC-CHANGE-003. Paths are relative to the configured context path. This document is normative for event semantics and transport bounds; current verification and deployment limits are documented in `docs/guide/technical/durable-queue.md`.

## Transport and authorization

- Method `GET`; an eligible server-side backend returns `200`, `Content-Type: text/event-stream;charset=UTF-8`, `Cache-Control: no-cache`, `Connection: close`, and `X-Accel-Buffering: no` where supported. Do not set a finite body length or buffer the whole stream. A server-side connector/protocol/capability mismatch returns JSON `503` before the SSE response is committed. Actual proxy/TLS client-path behavior remains a release gate, not a forwarded-header or request-time `503` decision.
- Use the same-origin authenticated Yona session cookie and current site-manager predicate as HTML/REST. Anonymous/expired session is JSON `401`; non-site-manager or pending-2FA is JSON `403`; never redirect to login. CORS is disabled. GET is read-only and needs no CSRF token.
- Retain caps of 32 active streams per application instance, 2 per principal, and 2 per session. One shared generation query per application runs at 1 Hz; one shared authority/session schedule revalidates each distinct principal and session at 1 Hz. No stream holds a DB connection or transaction.
- Replace blocking `SseEmitter` sends and the dedicated 32-thread writer pool with Servlet nonblocking output (`WriteListener`, `isReady()` before each bounded write) and a connection-bound Tomcat abort handle. A small `QueueSseHttp11NioProtocol`/`QueueSseHttp11Processor` subclass pair captures only the live `SocketWrapperBase`; no Coyote/Catalina `Response` is stored. No worker/transaction thread writes to SSE. At most one coalesced pending frame; callbacks and frame bytes are bounded.
- Runtime eligibility requires the exact instrumented `QueueSseHttp11NioProtocol`/`QueueSseHttp11Processor` pair, request protocol `HTTP/1.1`, backend connector `getSecure()==false` and protocol-handler `isSSLEnabled()==false`, a live connection-bound `SocketWrapperBase`, and a response chain with no compression/buffering for `text/event-stream` (including native Tomcat compression). Exclude this media type on `/events` only; if the effective route chain cannot be verified, return server-side JSON `503`. Do not use mutable `Request.isSecure()` or forwarded headers. A plain H1 NIO origin behind TLS offload may be eligible; release still requires actual proxy/TLS path proof. Direct native TLS, HTTP/2, stock uninstrumented H1NIO, other connectors, or missing handle is unsupported. No global HTTP changes.
- Before committing `200`, async mode, SSE headers, or body, an authorized request without the server-side eligibility checks/capability returns JSON `503` (`SSE_TRANSPORT_UNSUPPORTED`). Preserve `401`/`403` and quota `429`; external proxy/TLS probe status alone does not change this response.
- Set the oldest output-episode age with a monotonic clock before the first frame is handed to Servlet output. Keep it across partial writes, readiness/progress callbacks, coalescing, replacement, and new frames while any in-flight or pending app frame remains. Abort by age 1.5 seconds; actual server socket/channel close must be observed by age 2.0 seconds total. After a successful flush, clear age only at an atomic idle transition with no app-pending bytes/frames and the live nonblocking `WriteListener` still installed; call its public `ServletOutputStream.isReady()` under the stream lock either from the current `onWritePossible()` callback or the normal writer path. `true` on pinned Tomcat NIO means Tomcat userspace buffers drained, not OS TCP queue/peer ACK/client receipt; no new callback is required. Progress or readiness cannot clear age while app output remains.
- Serialize bounded writes/abort state with the captured wrapper's lock. Abort only marks the stream closed/pending under the lock; a shared external watchdog, after callbacks unwind, invokes `wrapper.setError(IOException)` then `wrapper.processSocket(SocketEvent.ERROR,false)` once. Never call this inline from a Tomcat callback or use direct `wrapper.close()`/raw `Response`/`CLOSE_NOW`. `AsyncListener.onError` calls `AsyncContext.complete()` only after normal ERROR handling makes response IO forbidden, to finish accounting; `WriteListener.onError` only marks/cleans. This is not a graceful-completion abort.
- Revocation/disable/logout closure budget: revocation-visible-to-abort ≤2.6s (1Hz shared checks, DB read ≤1s, authority lease ≤2.5s from check start, watchdog dispatch ≤0.1s) + abort-to-physical-close ≤2s = ≤4.6s, within the 5s requirement. These check/lease windows overlap; the actual detection-to-abort cap is 2.6s. Failed checks/expired leases stop writes immediately.
- The abort handle is request/connection-scoped, holds only the unique accepted-connection `SocketWrapperBase`, and is one-shot. Set `Connection: close` on successful SSE responses to prevent same-socket next-request reuse. Do not retain/reuse a response object.
- Set Servlet async timeout to zero, but do not assume it prevents Tomcat's forced TIMEOUT on shutdown. Before server destruction, a synchronous `ContextClosedEvent` handler stops SSE admissions, externally dispatches ERROR,false for active SSE handles, and waits for physical close/error finalization; no `@Async`, no callback-thread redispatch, and no unrelated async connection closure.

## Event grammar

Each event is UTF-8 and follows WHATWG SSE framing: zero or one `event:` line, zero or more `data:` lines, blank line terminator. JSON is compact UTF-8 on one `data:` line. Do not send payload bytes, exception stack traces, credential-bearing URLs, secrets, absolute internal paths, or arbitrary handler logs. Heartbeat is a comment line, not a synthetic job update.

### `reset`

Sent immediately after every connection/reconnection and after a client buffer overflows/coalesces. It is an instruction to fetch the current filtered job snapshot from REST, not a snapshot itself.

```text
event: reset
data: {"reason":"connect"}

```

`reason` is one of `connect`, `reconnect`, `buffer-overflow`, `server-restart`, `cursor-unavailable`. Client MUST refetch the page via REST. A page snapshot's `snapshotGeneration` supplies its read watermark. The UI does not reconstruct current truth from received SSE events.

### `changed`

Emitted after a committed mutation increments the shared DB queue generation. It is an invalidation hint; client refreshes the currently displayed page/detail from REST. Do not assume a particular job is visible in the current filter.

```text
event: changed
data: {"generation":"3021"}

```

`generation` is a monotonically increasing signed 64-bit sequence encoded as a decimal string. To limit unnecessary refreshes, coalesce multiple generation changes to at most one pending `changed` event per client. On a healthy stream, a commit is fanned out within 2 seconds; network disconnection is handled by EventSource reconnect plus `reset`.

### Heartbeat

Send a comment heartbeat no less frequently than every 15 seconds while an otherwise idle stream is alive:

```text
: heartbeat

```

The browser ignores the heartbeat. It does not refresh the job list, count as a job event, or imply authorization remains valid indefinitely. Writes use the same bounded readiness, outstanding-age, and abort serialization as every frame; a dead/aborted response is unregistered and receives no further writes.

## Replay, convergence, ordering, and client behavior

This protocol is intentionally **not an event-sourced history**. `Last-Event-ID` may be sent by EventSource and MUST be accepted syntactically, but server is not required to replay past invalidations. It may omit SSE `id:` entirely. Every new/reconnected stream starts with `reset`, and durable state is re-read from REST. This removes dependence on process-local emitter/event buffers and makes server restart safe.

The `changed` generation represents committed queue changes, not individual attempts. It advances transactionally with admission, state transitions, progress snapshots, and audited admin commands. It may coalesce several changes. Sequence gaps are normal; clients never infer lost job data from a gap. Every SSE frame is safe to drop: the client can always reload the persisted page/detail. Progress writes are coalesced at the worker side (at most one DB update per job per five seconds except stage transitions), not emitted from an unbounded in-memory event list.

Client algorithm:

1. Fetch queue page from `GET /jobs` on page entry.
2. Open `EventSource` to `/events` with same-origin credentials.
3. On `reset` or `changed`, fetch the current page/detail; cancel any superseded request and retain only the newest refresh. Do not use event content as authoritative job state.
4. On EventSource error, preserve the displayed last snapshot as stale, let native EventSource reconnect, and refresh when `reset` arrives. Display a bounded connection/stale indicator.
5. On intentional page exit, close EventSource; do not create a parallel fallback polling loop.

## Resource and privacy limits

No more than one shared DB generation poller per application instance, one shared authority revalidation schedule per active principal, one shared session-validity schedule per distinct session ID, and bounded stream registrations. The limits are 32 active streams per application, 2 per principal, and 2 per session; excess gets `429` before headers commit. There is no application writer pool or per-connection poller. Queue payload, filenames, errors, and result bytes are never transported over SSE. Only same-origin authorized admins see `changed`/`reset`; events include no hidden payload or result content. Failed/expired authority closes affected streams and discards pending messages.

## Required observable checks

- Context-path installation, initial `reset`, `text/event-stream`, comment heartbeat, state-change convergence, reconnection after server restart, and dropped/coalesced events still converge by DB fetch.
- Revoke site-admin role, disable user, and invalidate/logout session while a stream remains open: failed check/lease expiry stops writes, and actual server-side socket closure occurs within 5 seconds from revocation becoming visible to the application. Measure check scheduling, DB completion, watchdog dispatch, abort request, and physical close separately; revoke-to-abort is ≤2.6s, abort-to-close ≤2s, totaling ≤4.6s; the check/DB/lease limits overlap. `onComplete` or registry removal is insufficient.
- Create many slow/disconnected stream clients: active queue worker completion time and database connection count remain within fixed configured bounds; no client owns a polling loop/connection.
- Non-site admin / anonymous clients cannot open stream or infer job existence; query strings do not contain secrets or CSRF material.
