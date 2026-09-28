#!/usr/bin/env python3
"""PR04 live-HTTP acceptance extensions; uses only the real two-JVM queue fixture."""
from __future__ import annotations

import email.parser
import gzip
import http.client
import json
import os
import pathlib
import signal
import subprocess
import socket
import struct
import time
import unittest
import urllib.error
import urllib.request
import uuid
from urllib.parse import urlsplit

API = "/api/admin/queue/v1"
TEST = "/__test__/queue/v1"
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
PRESSURE_LIMIT = 8 * 1024 * 1024


def node2_health(base: str) -> bool:
    try:
        status, _, raw = request(base, TEST + "/health", control=True, timeout=1)
        return status == 200 and json.loads(raw) == {"status": "ready"}
    except (OSError, TimeoutError, urllib.error.URLError):
        return False


def restart_node2(base: str) -> subprocess.Popen:
    required = ("YONA_QUEUE_NODE_2_PID", "YONA_QUEUE_NODE_2_ENV_JSON",
                "YONA_QUEUE_TEST_JAVA", "YONA_QUEUE_TEST_CP", "YONA_QUEUE_TEST_MAIN",
                "YONA_QUEUE_NODE_2_PORT", "YONA_QUEUE_NODE_2_LOG", "YONA_QUEUE_REPO_DIR")
    for key in required:
        setting(key)
    old_pid = int(setting("YONA_QUEUE_NODE_2_PID"))
    try:
        os.kill(old_pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    deadline = time.monotonic() + 30
    while node2_health(base) and time.monotonic() < deadline:
        time.sleep(0.05)
    if node2_health(base):
        try:
            os.kill(old_pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        deadline = time.monotonic() + 10
        while node2_health(base) and time.monotonic() < deadline:
            time.sleep(0.05)
    if node2_health(base):
        raise AssertionError("node two did not stop before restart")

    node_env = json.loads(setting("YONA_QUEUE_NODE_2_ENV_JSON"))
    log = pathlib.Path(setting("YONA_QUEUE_NODE_2_LOG")).open("ab")
    process = subprocess.Popen(
        [setting("YONA_QUEUE_TEST_JAVA"), "-cp", setting("YONA_QUEUE_TEST_CP"),
         setting("YONA_QUEUE_TEST_MAIN"),
         "--server.port=" + setting("YONA_QUEUE_NODE_2_PORT"),
         "--server.address=127.0.0.1"],
        cwd=setting("YONA_QUEUE_REPO_DIR"), env=node_env, stdout=log, stderr=subprocess.STDOUT,
    )
    process._queue_acceptance_log = log
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if process.poll() is not None:
            stop_restarted_node(process)
            raise AssertionError("restarted node two exited before readiness")
        if node2_health(base):
            return process
        time.sleep(0.1)
    stop_restarted_node(process)
    raise AssertionError("restarted node two did not become ready")


def stop_restarted_node(process: subprocess.Popen) -> None:
    try:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)
    finally:
        process._queue_acceptance_log.close()


def setting(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        raise RuntimeError(f"missing fixture setting {name}")
    return value


def request(base: str, path: str, *, method: str = "GET", body: object | None = None,
            cookie: str | None = None, control: bool = False, headers: dict[str, str] | None = None,
            timeout: float = 10) -> tuple[int, object, bytes]:
    actual_headers = {"Accept": "application/json", **(headers or {})}
    data = None
    if body is not None:
        data = json.dumps(body, separators=(",", ":")).encode()
        actual_headers["Content-Type"] = "application/json"
    if cookie:
        actual_headers["Cookie"] = cookie
    if control:
        actual_headers["X-Yona-Queue-Test-Control"] = setting("YONA_QUEUE_TEST_CONTROL_TOKEN")
    req = urllib.request.Request(base.rstrip("/") + path, data=data, headers=actual_headers, method=method)
    try:
        response = HTTP.open(req, timeout=timeout)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        raw = response.read()
        return response.status, response.headers, raw


def request_http10(base: str, path: str, cookie: str | None = None) -> tuple[int, object, bytes]:
    parts = urlsplit(base)
    connection = http.client.HTTPConnection(parts.hostname, parts.port or 80, timeout=3)
    connection._http_vsn = 10
    connection._http_vsn_str = "HTTP/1.0"
    target = (parts.path.rstrip("/") + path) or path
    headers = {"Accept": "text/event-stream", "Accept-Encoding": "identity"}
    if cookie:
        headers["Cookie"] = cookie
    try:
        connection.request("GET", target, headers=headers)
        response = connection.getresponse()
        raw = b"" if response.status == 200 else response.read()
        return response.status, response.headers, raw
    finally:
        connection.close()




def new_session(base: str, role: str) -> str:
    status, _, raw = request(base, TEST + "/sessions", method="POST", body={"role": role}, control=True)
    if status != 200:
        raise AssertionError(f"fixture session {role} returned HTTP {status}: {raw[:300]!r}")
    return json.loads(raw)["cookie"]


def open_sse(base: str, cookie: str, *, last_event_id: str | None = None,
             accept_encoding: str = "identity", origin: str | None = None,
             pressure: bool = False, timeout: float = 10):
    parts = urlsplit(base)
    connection = http.client.HTTPConnection(parts.hostname, parts.port or 80, timeout=timeout)
    path = (parts.path.rstrip("/") + API + "/events") or (API + "/events")
    headers = {"Accept": "text/event-stream", "Accept-Encoding": accept_encoding, "Cookie": cookie}
    if last_event_id is not None:
        headers["Last-Event-ID"] = last_event_id
    if origin is not None:
        headers["Origin"] = origin
    if pressure:
        # Test-only header arms bounded legal comment pressure on this real product stream.
        headers["X-Yona-Queue-Test-Control"] = setting("YONA_QUEUE_TEST_CONTROL_TOKEN")
        headers["X-Yona-Queue-Test-Pressure"] = "bounded-comments"
    connection.connect()
    connection.queue_client_port = connection.sock.getsockname()[1]
    connection.queue_client_address = connection.sock.getsockname()[0]
    connection.queue_reset_socket = connection.sock.dup()
    connection.queue_server_port = parts.port or 80
    connection.queue_server_address = parts.hostname or "127.0.0.1"
    connection.queue_server_pid = int(setting(
        "YONA_QUEUE_NODE_2_PID" if base.rstrip("/") == setting("YONA_NODE_2_BASE_URL").rstrip("/")
        else "YONA_QUEUE_NODE_1_PID"
    ))
    try:
        connection.request("GET", path, headers=headers)
        response = connection.getresponse()
        return connection, response
    except BaseException:
        reset_sse_connection(connection, None)
        raise


def reset_sse_connection(
    connection: http.client.HTTPConnection,
    response: http.client.HTTPResponse | None,
) -> None:
    reset_socket = getattr(connection, "queue_reset_socket", None)
    if reset_socket is not None and reset_socket.fileno() >= 0:
        reset_socket.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
        reset_socket.close()
    if response is not None:
        response.close()
    connection.close()


def close_sse_and_wait_many(
    base: str,
    streams: list[tuple[http.client.HTTPConnection, http.client.HTTPResponse]],
    timeout: float = 16,
) -> None:
    deadline = time.monotonic() + timeout
    accepted = []
    for connection, response in streams:
        stream_id = response.headers.get("X-Yona-Queue-Test-Stream-ID")
        if response.status == 200:
            if not stream_id:
                raise AssertionError("successful SSE response omitted its observer ID")
            pid = connection.queue_server_pid
            server_port = connection.queue_server_port
            client_port = connection.queue_client_port
            server_address = connection.queue_server_address
            client_address = connection.queue_client_address
            remaining = max(0.1, deadline - time.monotonic())
            last_present = wait_fd_open(
                pid, server_port, client_port, server_address, client_address, timeout=remaining,
            )
            accepted.append((
                stream_id, pid, server_port, client_port, server_address, client_address, last_present,
            ))
    for connection, response in streams:
        reset_sse_connection(connection, response)
    for stream_id, pid, server_port, client_port, server_address, client_address, last_present in accepted:
        remaining = max(0.1, deadline - time.monotonic())
        wait_stream(base, stream_id, lambda state: state.get("unregistered") is True, remaining)
        remaining = max(0.1, deadline - time.monotonic())
        wait_fd_closed(
            pid, server_port, client_port, server_address, client_address, remaining, last_present,
        )


def close_sse_and_wait(base: str, connection: http.client.HTTPConnection,
                       response: http.client.HTTPResponse, timeout: float = 16) -> None:
    close_sse_and_wait_many(base, [(connection, response)], timeout)


def assert_quota_rejected(base: str, cookie: str, reason: str) -> None:
    connection, response = open_sse(base, cookie)
    try:
        if response.status != 429:
            raise AssertionError(f"{reason}: expected HTTP 429 before SSE commitment, got {response.status}")
        if not response.headers.get("Retry-After"):
            raise AssertionError(f"{reason}: HTTP 429 must include Retry-After")
    finally:
        reset_sse_connection(connection, response)


def read_frame(response: http.client.HTTPResponse, timeout: float = 16) -> dict[str, object]:
    sock = response.fp.raw._sock
    sock.settimeout(timeout)
    deadline = time.monotonic() + timeout
    lines: list[bytes] = []
    while time.monotonic() < deadline:
        line = response.readline()
        if line == b"":
            raise AssertionError("SSE response ended before a complete frame")
        if line in (b"\n", b"\r\n"):
            if not lines:
                continue
            result: dict[str, object] = {"event": "message", "data": "", "comments": []}
            comments: list[str] = []
            data: list[str] = []
            for raw_line in lines:
                line_text = raw_line.rstrip(b"\r\n").decode("utf-8")
                if line_text.startswith(":"):
                    comments.append(line_text[1:].lstrip())
                elif line_text.startswith("event:"):
                    result["event"] = line_text[6:].strip()
                elif line_text.startswith("data:"):
                    data.append(line_text[5:].lstrip())
            result["comments"] = comments
            result["data"] = "\n".join(data)
            return result
        lines.append(line)
    raise AssertionError("SSE frame deadline expired")


def test_call(base: str, scenario: str, *, cookie: str | None = None, **options: object) -> dict[str, object]:
    status, _, raw = request(base, TEST + "/runs", method="POST",
                             body={"scenario": scenario, "runKey": str(uuid.uuid4()), **options},
                             cookie=cookie, control=True)
    if status != 200:
        raise AssertionError(f"real queue scenario returned HTTP {status}: {raw[:300]!r}")
    return json.loads(raw)


def test_action(base: str, run_id: str, action: str) -> tuple[int, int]:
    status, headers, raw = request(base, TEST + f"/runs/{run_id}/actions/{action}",
                                   method="POST", body={}, control=True)
    if status != 204:
        raise AssertionError(f"fixture action {action} returned HTTP {status}: {raw[:300]!r}")
    started_at = headers.get("X-Yona-Queue-Test-Action-Started-Monotonic-Nanos")
    committed_at = headers.get("X-Yona-Queue-Test-Commit-Monotonic-Nanos")
    if not started_at or not committed_at:
        raise AssertionError("fixture action omitted its mutation-start/post-commit timestamps")
    return int(started_at), int(committed_at)

def job(base: str, job_id: str) -> dict[str, object]:
    status, _, raw = request(base, API + f"/jobs/{job_id}", cookie=setting("YONA_ADMIN_COOKIE"))
    if status != 200:
        raise AssertionError(f"real queue job read returned HTTP {status}: {raw[:300]!r}")
    return json.loads(raw)


def wait_job(base: str, job_id: str, expected: str, timeout: float = 30) -> dict[str, object]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        current = job(base, job_id)
        if current["status"] == expected:
            return current
        time.sleep(0.05)
    raise AssertionError(f"real queue job {job_id} did not reach {expected}")


def timed_metrics(base: str) -> tuple[dict[str, object], int, int, int]:
    before = time.monotonic_ns()
    status, _, raw = request(base, TEST + "/sse/metrics", control=True)
    after = time.monotonic_ns()
    if status != 200:
        raise AssertionError(f"private SSE observer returned HTTP {status}: {raw[:300]!r}")
    report = json.loads(raw)
    server_now = int(report["observedAtMonotonicNanos"])
    return report, server_now - after, server_now - before, after - before


def metrics(base: str) -> dict[str, object]:
    return timed_metrics(base)[0]


def wait_stream_sample(base: str, stream_id: str, predicate, timeout: float):
    deadline = time.monotonic() + timeout
    last: dict[str, object] = {}
    last_clock = (0, 0, 0)
    while time.monotonic() < deadline:
        report, offset_lower, offset_upper, rtt = timed_metrics(base)
        last_clock = (offset_lower, offset_upper, rtt)
        streams = report.get("streams", {})
        last = streams.get(stream_id, {})
        if predicate(last):
            return last, last_clock
        time.sleep(0.025)
    raise AssertionError(f"stream {stream_id} did not reach expected transport state: {last}")


def wait_stream(base: str, stream_id: str, predicate, timeout: float) -> dict[str, object]:
    return wait_stream_sample(base, stream_id, predicate, timeout)[0]


def wait_saturated_stream(base: str, stream_id: str, timeout: float = 5) -> dict[str, object]:
    deadline = time.monotonic() + timeout
    first_sample_at: float | None = None
    first_outstanding: int | None = None
    previous_readiness_checks: int | None = None
    consecutive = 0
    latest: dict[str, object] = {}
    while time.monotonic() < deadline:
        latest = metrics(base).get("streams", {}).get(stream_id, {})
        live_pending = (
            latest.get("unregistered") is not True and
            int(latest.get("pendingOutputBytes", 0)) > 0
        )
        observed_age = int(latest.get("firstOutstandingMonotonicNanos", 0))
        if live_pending and first_outstanding is not None and observed_age != first_outstanding:
            raise AssertionError("oldest outstanding age changed while bytes remained pending")
        ready_false = (
            live_pending and observed_age > 0 and
            latest.get("isReadyFalseObserved") is True and
            int(latest.get("pressureBytesAttempted", 0)) > 0
        )
        now = time.monotonic()
        if ready_false:
            if first_outstanding is None:
                first_outstanding = observed_age
            readiness_checks = int(latest.get("readinessCheckCount", 0))
            if previous_readiness_checks is None or readiness_checks > previous_readiness_checks:
                if consecutive == 0:
                    first_sample_at = now
                consecutive += 1
            else:
                consecutive = 0
                first_sample_at = None
            previous_readiness_checks = readiness_checks
            if consecutive >= 3 and first_sample_at is not None and now - first_sample_at >= 0.10:
                return latest
        else:
            consecutive = 0
            first_sample_at = None
            previous_readiness_checks = None
        time.sleep(0.05)
    raise AssertionError(f"stream {stream_id} never sustained pending output and repeated native no-ready checks: {latest}")


def clock_ping(base: str) -> tuple[int, int, int, int]:
    before = time.monotonic_ns()
    status, _, raw = request(base, TEST + "/sse/clock", control=True)
    after = time.monotonic_ns()
    if status != 200:
        raise AssertionError(f"private monotonic clock ping returned HTTP {status}: {raw[:300]!r}")
    server_now = int(json.loads(raw)["monotonicNanos"])
    return server_now - after, server_now - before, after - before, server_now


def fd_open(pid: int, server_port: int, client_port: int,
            server_address: str, client_address: str) -> bool:
    result = subprocess.run(
        ["lsof", "-nP", "-a", "-p", str(pid), "-iTCP"],
        capture_output=True, text=True, timeout=2,
    )
    if result.returncode not in (0, 1):
        raise AssertionError(f"lsof failed while observing server TCP FDs: {result.stderr.strip()}")
    for line in result.stdout.splitlines():
        endpoint = next((field for field in line.split() if "->" in field), "")
        if not endpoint:
            continue
        local, remote = endpoint.split("->", 1)
        local_address, local_port = local.rsplit(":", 1)
        remote_address, remote_port = remote.rsplit(":", 1)
        if (local_address.strip("[]") == server_address and local_port == str(server_port) and
            remote_address.strip("[]") == client_address and remote_port == str(client_port)):
            return True
    return False


def wait_fd_open(pid: int, server_port: int, client_port: int,
                 server_address: str, client_address: str, timeout: float = 5) -> int:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        sample_started = time.monotonic_ns()
        if fd_open(pid, server_port, client_port, server_address, client_address):
            return sample_started
        time.sleep(0.05)
    raise AssertionError("lsof did not observe the exact server/client address-and-port tuple")


def wait_fd_closed(pid: int, server_port: int, client_port: int,
                   server_address: str, client_address: str, timeout: float,
                   last_present_started_nanos: int) -> tuple[int, int]:
    deadline = time.monotonic() + timeout
    last_present_start = last_present_started_nanos
    while time.monotonic() < deadline:
        sample_started = time.monotonic_ns()
        present = fd_open(pid, server_port, client_port, server_address, client_address)
        sample_finished = time.monotonic_ns()
        if not present:
            return last_present_start, sample_finished
        last_present_start = sample_started
        time.sleep(0.05)
    raise AssertionError("OS still reports the exact server/client address-and-port tuple")


def listener_open(pid: int, port: int) -> bool:
    result = subprocess.run(
        ["lsof", "-nP", "-a", "-p", str(pid), "-iTCP"],
        capture_output=True, text=True, timeout=2,
    )
    if result.returncode not in (0, 1):
        raise AssertionError(f"lsof failed while observing the Tomcat listener: {result.stderr.strip()}")
    return any(
        "(LISTEN)" in line and
        any(field.endswith(f":{port}") for field in line.split() if "->" not in field)
        for line in result.stdout.splitlines()
    )


def wait_listener_closed(pid: int, port: int, timeout: float = 30) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not listener_open(pid, port):
            return
        time.sleep(0.05)
    raise AssertionError("Tomcat listening FD remained open after ContextClosedEvent")


def lifecycle_state(base: str) -> dict[str, object]:
    status, _, raw = request(base, TEST + "/sse/lifecycle", control=True)
    if status != 200:
        raise AssertionError(f"private Tomcat lifecycle observer returned HTTP {status}: {raw[:300]!r}")
    return json.loads(raw)


def wait_lifecycle_marker(path: pathlib.Path, timeout: float = 30) -> dict[str, object]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.exists():
            return json.loads(path.read_text())
        time.sleep(0.025)
    raise AssertionError(f"pre-stop async-zero lifecycle record did not appear: {path}")


def assert_close_timing(testcase: unittest.TestCase, base: str, state: dict[str, object],
                        action_started_nanos: int, committed_nanos: int,
                        fd_interval: tuple[int, int]) -> None:
    _, offset_upper, rtt, _ = clock_ping(base)
    testcase.assertLessEqual(rtt, 250_000_000, "clock-ping uncertainty is too large for the deadline proof")
    fd_close_upper = fd_interval[1] + offset_upper

    outstanding = int(state.get("firstOutstandingMonotonicNanos", 0))
    abort = int(state.get("abortRequestMonotonicNanos", 0))
    if outstanding:
        testcase.assertGreater(abort, 0)
        testcase.assertLessEqual(abort - outstanding, 1_500_000_000,
                                 "watchdog must abort by the 1.5s oldest-output deadline")
        testcase.assertLessEqual(fd_close_upper - outstanding, 2_000_000_000,
                                 "first outstanding output must reach the OS-observed FD close within 2s")
    if abort:
        testcase.assertLessEqual(fd_close_upper - abort, 2_000_000_000,
                                 "abort-to-physical-close must be at most 2s")

    failed_check = int(state.get("authorizationFailureMonotonicNanos", 0))
    if failed_check:
        testcase.assertGreater(abort, 0)
        testcase.assertLessEqual(abort - action_started_nanos, 2_600_000_000,
                                 "revocation start through failed-check abort exceeded the detection budget")
        testcase.assertEqual(int(state.get("applicationWritesAfterAuthFailure", 0)), 0)
    testcase.assertLessEqual(fd_close_upper - action_started_nanos, 5_000_000_000,
                             "revocation start to OS-observed close exceeded 5s")
    testcase.assertLessEqual(fd_close_upper - committed_nanos, 5_000_000_000,
                             "revocation commit to OS-observed close exceeded 5s")



class SseSocketReader:
    """Raw HTTP/1.1 reader supporting chunked or Connection-close SSE bodies."""

    def __init__(self, base: str, cookie: str):
        parts = urlsplit(base)
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
            self.sock.settimeout(10)
            self.sock.connect((parts.hostname or "127.0.0.1", parts.port or 80))
            self.server_port = parts.port or 80
            self.client_port = self.sock.getsockname()[1]
            self.server_address = parts.hostname or "127.0.0.1"
            self.client_address = self.sock.getsockname()[0]
            path = (parts.path.rstrip("/") + API + "/events") or (API + "/events")
            request_bytes = (
                f"GET {path} HTTP/1.1\r\nHost: {parts.netloc}\r\n"
                f"Accept: text/event-stream\r\nAccept-Encoding: identity\r\nCookie: {cookie}\r\n"
                f"X-Yona-Queue-Test-Control: {setting('YONA_QUEUE_TEST_CONTROL_TOKEN')}\r\n"
                "X-Yona-Queue-Test-Pressure: bounded-comments\r\nConnection: keep-alive\r\n\r\n"
            ).encode("ascii")
            self.sock.sendall(request_bytes)
            header_bytes = bytearray()
            while not header_bytes.endswith(b"\r\n\r\n"):
                part = self.sock.recv(1)
                if not part or len(header_bytes) > 65_536:
                    raise AssertionError("SSE response headers did not complete")
                header_bytes.extend(part)
            lines = bytes(header_bytes).split(b"\r\n")
            self.status = int(lines[0].split()[1])
            self.headers = email.parser.Parser().parsestr(
                "\n".join(line.decode("latin-1") for line in lines[1:-2])
            )
            self.stream_id = self.headers.get("X-Yona-Queue-Test-Stream-ID", "")
            self.chunked = "chunked" in self.headers.get("Transfer-Encoding", "").lower()
            self.close_delimited = not self.chunked and "close" in self.headers.get("Connection", "").lower()
            self.chunk_remaining = 0
            self.ended = False
            if self.chunked and self.headers.get("Content-Length"):
                raise AssertionError("stream must not combine chunked encoding with a fixed response length")
            if self.close_delimited and self.headers.get("Content-Length"):
                raise AssertionError("SSE stream must not set a finite response length")
            if not self.chunked and not self.close_delimited:
                raise AssertionError("SSE response must use chunked or HTTP connection-close framing")
        except BaseException:
            self.sock.close()
            raise

    def _socket_line(self) -> bytes:
        line = bytearray()
        while not line.endswith(b"\r\n"):
            part = self.sock.recv(1)
            if not part:
                raise EOFError("SSE socket closed before the HTTP response terminator")
            line.extend(part)
        return bytes(line)

    def _socket_exact(self, size: int) -> bytes:
        result = bytearray()
        while len(result) < size:
            part = self.sock.recv(size - len(result))
            if not part:
                raise EOFError("SSE socket closed inside an HTTP chunk")
            result.extend(part)
        return bytes(result)

    def _next_chunk(self) -> bool:
        if not self.chunked:
            raise AssertionError("chunk parser used for a close-delimited SSE body")
        size = int(self._socket_line().split(b";", 1)[0].strip(), 16)
        if size == 0:
            while self._socket_line() != b"\r\n":
                pass
            self.ended = True
            return False
        self.chunk_remaining = size
        return True


    def _body_byte(self) -> bytes:
        if self.ended:
            return b""
        if self.close_delimited:
            value = self.sock.recv(1)
            if not value:
                self.ended = True
                return b""
            return value
        if self.chunk_remaining == 0 and not self._next_chunk():
            return b""
        value = self.sock.recv(1)
        if not value:
            raise EOFError("SSE socket closed inside an HTTP chunk")
        self.chunk_remaining -= 1
        if self.chunk_remaining == 0:
            delimiter = self._socket_exact(2)
            if delimiter != b"\r\n":
                raise AssertionError("malformed HTTP chunk terminator")
        return value

    def read_frame(self) -> dict[str, object]:
        lines: list[bytes] = []
        while True:
            line = bytearray()
            while True:
                byte = self._body_byte()
                if not byte:
                    raise AssertionError("SSE stream ended before a complete frame")
                line.extend(byte)
                if byte == b"\n":
                    break
            if bytes(line) in (b"\n", b"\r\n"):
                result: dict[str, object] = {"event": "message", "data": "", "comments": []}
                comments: list[str] = []
                data: list[str] = []
                for raw_line in lines:
                    text = raw_line.rstrip(b"\r\n").decode("utf-8")
                    if text.startswith(":"):
                        comments.append(text[1:].lstrip())
                    elif text.startswith("event:"):
                        result["event"] = text[6:].strip()
                    elif text.startswith("data:"):
                        data.append(text[5:].lstrip())
                result["comments"] = comments
                result["data"] = "\n".join(data)
                return result
            lines.append(bytes(line))

    def drip_some(self, limit: int = 1024) -> bytes:
        """Read a small bounded amount without consuming or resetting SSE frame state."""
        data = bytearray()
        self.sock.settimeout(0.1)
        try:
            while len(data) < limit and not self.ended:
                if self.close_delimited:
                    try:
                        part = self.sock.recv(limit - len(data))
                    except socket.timeout:
                        break
                    if not part:
                        self.ended = True
                        break
                    data.extend(part)
                    continue
                if self.chunk_remaining == 0:
                    try:
                        if not self._next_chunk():
                            break
                    except socket.timeout:
                        break
                try:
                    part = self.sock.recv(min(limit - len(data), self.chunk_remaining))
                except socket.timeout:
                    break
                if not part:
                    raise EOFError("SSE socket closed while drip-reading")
                data.extend(part)
                self.chunk_remaining -= len(part)
                if self.chunk_remaining == 0 and self._socket_exact(2) != b"\r\n":
                    raise AssertionError("malformed HTTP chunk terminator")
        except (EOFError, ConnectionResetError, ConnectionAbortedError):
            self.ended = True
        return bytes(data)



    def reset(self) -> None:
        self.sock.close()


class SsePr04Acceptance(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        for key in ("YONA_BASE_URL", "YONA_NODE_2_BASE_URL", "YONA_ADMIN_COOKIE", "YONA_MEMBER_COOKIE",
                    "YONA_QUEUE_TEST_CONTROL_TOKEN",
                    "YONA_QUEUE_NODE_1_PID", "YONA_QUEUE_NODE_2_PID",
                    "YONA_QUEUE_NODE_2_ENV_JSON", "YONA_QUEUE_NODE_2_PORT", "YONA_QUEUE_NODE_2_LOG",
                    "YONA_QUEUE_TEST_JAVA", "YONA_QUEUE_TEST_CP", "YONA_QUEUE_TEST_MAIN",
                    "YONA_QUEUE_REPO_DIR"):
            setting(key)

    def test_pr04_http10_auth_precedes_unsupported_protocol_503(self) -> None:
        base = setting("YONA_BASE_URL")
        before = int(metrics(base)["activeStreams"])
        for cookie, expected in ((None, 401), (setting("YONA_MEMBER_COOKIE"), 403),
                                 (setting("YONA_ADMIN_COOKIE"), 503)):
            status, headers, raw = request_http10(base, API + "/events", cookie)
            self.assertEqual(status, expected)
            self.assertIsNone(headers.get("Location"), "unsupported/denied API requests must not redirect")
            self.assertIn("application/json", headers.get("Content-Type", ""))
            self.assertNotIn("text/event-stream", headers.get("Content-Type", ""))
            self.assertIsNone(headers.get("X-Accel-Buffering"))
            self.assertIsNone(headers.get("Transfer-Encoding"))
            self.assertFalse(raw.startswith(b"event:"), "HTTP errors must not include SSE frames")
            error = json.loads(raw)
            self.assertIsInstance(error, dict)
            if expected == 503:
                self.assertEqual(error.get("code"), "SSE_TRANSPORT_UNSUPPORTED")
        self.assertEqual(int(metrics(base)["activeStreams"]), before,
                         "unsupported protocol and auth failures must not register streams")

    def test_pr04_sse_bypasses_compression_for_gzip_acceptor(self) -> None:
        base = setting("YONA_BASE_URL")
        connection, response = open_sse(base, setting("YONA_ADMIN_COOKIE"), accept_encoding="gzip")
        try:
            self.assertEqual(response.status, 200)
            self.assertTrue(response.headers.get("Content-Type", "").startswith("text/event-stream"))
            self.assertIsNone(response.headers.get("Content-Encoding"),
                              "SSE event data must not be compressed or buffered")
            self.assertEqual(read_frame(response)["event"], "reset")
        finally:
            close_sse_and_wait(base, connection, response)

        status, headers, raw = request(
            base, API + "/jobs?limit=1", cookie=setting("YONA_ADMIN_COOKIE"),
            headers={"Accept-Encoding": "gzip"},
        )
        self.assertEqual(status, 200)
        self.assertIn("gzip", headers.get("Content-Encoding", ""))
        self.assertIn("snapshotGeneration", json.loads(gzip.decompress(raw)))

    def test_pr04_foreign_origin_does_not_receive_cors_headers(self) -> None:
        base = setting("YONA_BASE_URL")
        connection, response = open_sse(
            base, setting("YONA_ADMIN_COOKIE"), origin="https://foreign.invalid",
        )
        try:
            self.assertEqual(response.status, 200)
            self.assertEqual(read_frame(response)["event"], "reset")
            self.assertIsNone(response.headers.get("Access-Control-Allow-Origin"))
        finally:
            close_sse_and_wait(base, connection, response)

    def test_pr04_initial_reset_and_last_event_id_reconnect(self) -> None:
        base = setting("YONA_BASE_URL")
        connection, response = open_sse(base, setting("YONA_ADMIN_COOKIE"))
        try:
            self.assertEqual(response.status, 200)
            self.assertEqual(response.headers.get("Cache-Control"), "no-cache")
            self.assertEqual(response.headers.get("X-Accel-Buffering"), "no")
            self.assertEqual(response.headers.get("Connection", "").lower(), "close")
            self.assertIsNone(response.headers.get("Content-Encoding"))
            self.assertIsNone(response.headers.get("Content-Length"))
            first = read_frame(response)
            self.assertEqual(first["event"], "reset")
            self.assertEqual(json.loads(str(first["data"]))["reason"], "connect")
        finally:
            close_sse_and_wait(base, connection, response)

        connection, response = open_sse(base, setting("YONA_ADMIN_COOKIE"), last_event_id="3021")
        try:
            self.assertEqual(response.status, 200)
            replay = read_frame(response)
            self.assertEqual(replay["event"], "reset")
            self.assertIn(json.loads(str(replay["data"]))["reason"], {"connect", "reconnect"})
        finally:
            close_sse_and_wait(base, connection, response)

    def test_pr04_heartbeat_is_an_idle_comment_within_fifteen_seconds(self) -> None:
        base = setting("YONA_BASE_URL")
        connection, response = open_sse(base, setting("YONA_ADMIN_COOKIE"))
        try:
            self.assertEqual(read_frame(response)["event"], "reset")
            deadline = time.monotonic() + 15
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    self.fail("idle SSE stream did not send a heartbeat comment within 15 seconds")
                frame = read_frame(response, timeout=remaining)
                if "heartbeat" in frame["comments"]:
                    return
        finally:
            close_sse_and_wait(base, connection, response)

    def test_pr04_same_session_and_principal_caps_and_node_cap(self) -> None:
        base = setting("YONA_BASE_URL")
        cookie_a = new_session(base, "admin")
        cookie_b = new_session(base, "admin")
        opened: list[tuple[http.client.HTTPConnection, http.client.HTTPResponse]] = []
        try:
            for _ in range(2):
                connection, response = open_sse(base, cookie_a)
                self.assertEqual(response.status, 200)
                opened.append((connection, response))
            assert_quota_rejected(base, cookie_a, "third active stream for one session")

            close_sse_and_wait_many(base, opened)
            opened.clear()

            for cookie in (cookie_a, cookie_b):
                connection, response = open_sse(base, cookie)
                self.assertEqual(response.status, 200)
                opened.append((connection, response))
            assert_quota_rejected(base, cookie_b, "third active stream across sessions")
        finally:
            close_sse_and_wait_many(base, opened)

        node1 = setting("YONA_BASE_URL")
        node2 = setting("YONA_NODE_2_BASE_URL")
        node_streams: list[tuple[http.client.HTTPConnection, http.client.HTTPResponse]] = []
        try:
            for index in range(31):
                cookie = new_session(node1, f"capacity-admin-{index}")
                connection, response = open_sse(node1, cookie)
                self.assertEqual(response.status, 200, f"stream {index + 1}/32 should fit on node one")
                node_streams.append((connection, response))

            healthy_cookie = new_session(node1, "capacity-admin-31")
            healthy_connection, healthy_response = open_sse(node1, healthy_cookie)
            self.assertEqual(healthy_response.status, 200, "the 32nd stream is the healthy fanout observer")
            node_streams.append((healthy_connection, healthy_response))
            self.assertEqual(read_frame(healthy_response)["event"], "reset")

            first_run = test_call(node1, "success", resourceKey=f"sse-at-cap:{uuid.uuid4()}")
            wait_job(node1, first_run["jobIds"][0], "SUCCEEDED")
            status, _, raw = request(node1, API + "/jobs?limit=1", cookie=setting("YONA_ADMIN_COOKIE"))
            self.assertEqual(status, 200)
            watermark = int(json.loads(raw)["snapshotGeneration"])
            deadline = time.monotonic() + 2
            seen = 0
            while time.monotonic() < deadline:
                frame = read_frame(healthy_response, timeout=max(0.05, deadline - time.monotonic()))
                if frame["event"] == "changed":
                    seen = max(seen, int(json.loads(str(frame["data"]))["generation"]))
                    if seen >= watermark:
                        break
            self.assertGreaterEqual(seen, watermark, "healthy stream must converge with 31 slow streams at cap")
            stream_id = healthy_response.headers["X-Yona-Queue-Test-Stream-ID"]
            drained = wait_stream(
                node1, stream_id,
                lambda state: int(state.get("pendingOutputBytes", -1)) == 0 and
                int(state.get("firstOutstandingMonotonicNanos", -1)) == 0,
                timeout=1,
            )
            self.assertEqual(int(drained["pendingOutputBytes"]), 0)

            second_run = test_call(node1, "success", resourceKey=f"sse-at-cap-second:{uuid.uuid4()}")
            wait_job(node1, second_run["jobIds"][0], "SUCCEEDED")
            status, _, raw = request(node1, API + "/jobs?limit=1", cookie=setting("YONA_ADMIN_COOKIE"))
            self.assertEqual(status, 200)
            second_watermark = int(json.loads(raw)["snapshotGeneration"])
            self.assertGreater(second_watermark, watermark)
            deadline = time.monotonic() + 2
            second_seen = 0
            while time.monotonic() < deadline:
                frame = read_frame(healthy_response, timeout=max(0.05, deadline - time.monotonic()))
                if frame["event"] == "changed":
                    second_seen = max(second_seen, int(json.loads(str(frame["data"]))["generation"]))
                    if second_seen >= second_watermark:
                        break
            self.assertGreaterEqual(second_seen, second_watermark,
                                    "healthy stream must remain live after the prior output drain")

            extra = new_session(node1, "capacity-admin-32")
            assert_quota_rejected(node1, extra, "33rd stream on one node")
            other_node_cookie = new_session(node2, "capacity-admin-33")
            connection, response = open_sse(node2, other_node_cookie)
            try:
                self.assertEqual(response.status, 200, "node quota is per application instance")
            finally:
                close_sse_and_wait(node2, connection, response)
        finally:
            close_sse_and_wait_many(node1, node_streams)

    def test_pr04_shared_generation_poll_and_streams_own_no_db_connection(self) -> None:
        base = setting("YONA_BASE_URL")
        cookies = [new_session(base, role) for role in ("admin", "revoke-admin")]
        opened: list[tuple[http.client.HTTPConnection, http.client.HTTPResponse]] = []
        try:
            for cookie in cookies:
                for _ in range(2):
                    connection, response = open_sse(base, cookie)
                    self.assertEqual(response.status, 200)
                    opened.append((connection, response))
            before = metrics(base)
            pool_before = json.loads(request(base, TEST + "/pool", control=True)[2])
            time.sleep(6.2)
            after = metrics(base)
            pool_after = json.loads(request(base, TEST + "/pool", control=True)[2])
            polls = int(after["generationPollCount"]) - int(before["generationPollCount"])
            self.assertGreaterEqual(polls, 5, "one shared generation poller should run at about 1 Hz")
            self.assertLessEqual(polls, 9, "opening streams must not create per-stream DB pollers")
            principal_checks = int(after["principalAuthCheckCount"]) - int(before["principalAuthCheckCount"])
            session_checks = int(after["sessionValidityCheckCount"]) - int(before["sessionValidityCheckCount"])
            self.assertGreaterEqual(principal_checks, 8, "distinct principal checks must run at 1 Hz")
            self.assertLessEqual(principal_checks, 16, "duplicate streams must share each principal check")
            self.assertGreaterEqual(session_checks, 8, "distinct session checks must run at 1 Hz")
            self.assertLessEqual(session_checks, 16, "duplicate streams must share each session check")
            self.assertLessEqual(int(pool_after["active"]), int(pool_before["active"]) + 1,
                                 "live SSE streams must not retain queue DB connections")
            self.assertLessEqual(int(after["maxPendingFramesPerStream"]), 1)
        finally:
            close_sse_and_wait_many(base, opened)

    def test_pr04_real_queue_burst_coalesces_to_latest_rest_watermark(self) -> None:
        base = setting("YONA_BASE_URL")
        connection, response = open_sse(base, setting("YONA_ADMIN_COOKIE"))
        try:
            self.assertEqual(read_frame(response)["event"], "reset")
            runs = [test_call(base, "success", resourceKey=f"sse-burst:{uuid.uuid4()}") for _ in range(8)]
            for run in runs:
                wait_job(base, run["jobIds"][0], "SUCCEEDED")
            status, _, raw = request(base, API + "/jobs?limit=1", cookie=setting("YONA_ADMIN_COOKIE"))
            self.assertEqual(status, 200)
            watermark = int(json.loads(raw)["snapshotGeneration"])
            deadline = time.monotonic() + 2
            seen = 0
            while time.monotonic() < deadline:
                frame = read_frame(response, timeout=max(0.05, deadline - time.monotonic()))
                if frame["event"] != "changed":
                    continue
                seen = max(seen, int(json.loads(str(frame["data"]))["generation"]))
                if seen >= watermark:
                    break
            self.assertGreaterEqual(seen, watermark, "coalesced/stale signals must converge to durable REST state")
            self.assertLessEqual(int(metrics(base)["maxPendingFramesPerStream"]), 1)
        finally:
            close_sse_and_wait(base, connection, response)

    def test_pr04_authority_failure_and_physical_close_from_mutation(self) -> None:
        base = setting("YONA_BASE_URL")
        cases = (
            ("revoke-admin", "revoke-test-manager", "restore-test-manager", 403),
            ("disabled-admin", "disable-test-manager", "restore-disabled-test-manager", None),
            ("session-admin", "invalidate-test-manager-session", None, 401),
        )
        pid = int(setting("YONA_QUEUE_NODE_1_PID"))
        server_port = urlsplit(base).port or 80
        for role, action, restore, expected_status in cases:
            run = None
            connection = None
            response = None
            mutation_attempted = False
            try:
                cookie = new_session(base, role)
                run = test_call(base, "success", cookie=cookie, resourceKey=f"sse-auth-close:{uuid.uuid4()}")
                wait_job(base, run["jobIds"][0], "SUCCEEDED")
                connection, response = open_sse(base, cookie)
                stream_id = response.headers.get("X-Yona-Queue-Test-Stream-ID", "")
                self.assertTrue(stream_id, "real SSE response must expose its private observer ID")
                client_port = connection.queue_client_port
                server_address = connection.queue_server_address
                client_address = connection.queue_client_address
                self.assertEqual(read_frame(response)["event"], "reset")
                last_present = wait_fd_open(pid, server_port, client_port, server_address, client_address)
                mutation_attempted = True
                action_started, committed = test_action(base, run["runId"], action)
                state = wait_stream(
                    base, stream_id,
                    lambda item: int(item.get("authorizationFailureMonotonicNanos", 0)) > 0 and
                    item.get("unregistered") is True,
                    timeout=5,
                )
                fd_interval = wait_fd_closed(
                    pid, server_port, client_port, server_address, client_address, timeout=5,
                    last_present_started_nanos=last_present,
                )
                assert_close_timing(self, base, state, action_started, committed, fd_interval)
                self.assertEqual(int(state.get("applicationWritesAfterAuthFailure", 0)), 0)

                status, _, _ = request(base, API + "/jobs", cookie=cookie)
                if expected_status is not None:
                    self.assertEqual(status, expected_status)
                else:
                    self.assertIn(status, (401, 403), "disabled account must fail closed on the REST queue")
                test_call(base, "success", resourceKey=f"sse-after-auth-failure:{uuid.uuid4()}")
                final_state = wait_stream(base, stream_id,
                                          lambda item: item.get("unregistered") is True, timeout=1)
                self.assertEqual(int(final_state.get("applicationWritesAfterAuthFailure", 0)), 0)
            finally:
                try:
                    if connection is not None:
                        reset_sse_connection(connection, response)
                finally:
                    if mutation_attempted and restore and run is not None:
                        test_action(base, run["runId"], restore)

    def test_pr04_saturated_real_stream_revocation_closes_within_five_seconds(self) -> None:
        base = setting("YONA_BASE_URL")
        cases = (
            ("revoke-admin", "revoke-test-manager", "restore-test-manager", 403),
            ("disabled-admin", "disable-test-manager", "restore-disabled-test-manager", None),
            ("session-admin", "invalidate-test-manager-session", None, 401),
        )
        pid = int(setting("YONA_QUEUE_NODE_1_PID"))
        server_port = urlsplit(base).port or 80
        for role, action, restore, expected_status in cases:
            run = None
            stream = None
            mutation_attempted = False
            try:
                cookie = new_session(base, role)
                run = test_call(base, "success", cookie=cookie, resourceKey=f"sse-stall:{uuid.uuid4()}")
                wait_job(base, run["jobIds"][0], "SUCCEEDED")
                stream = SseSocketReader(base, cookie)
                self.assertEqual(stream.status, 200)
                self.assertTrue(stream.stream_id, "live product SSE response must expose the test-only stream observer ID")
                self.assertIn("text/event-stream", stream.headers.get("Content-Type", ""))
                self.assertIsNone(stream.headers.get("Content-Encoding"))
                last_present = wait_fd_open(
                    pid, server_port, stream.client_port, stream.server_address, stream.client_address,
                )
                self.assertEqual(stream.read_frame()["event"], "reset")
                stalled = wait_saturated_stream(base, stream.stream_id)
                self.assertGreater(int(stalled["pressureBytesAttempted"]), 0)
                self.assertLessEqual(int(stalled["pressureBytesAttempted"]), PRESSURE_LIMIT)
                self.assertFalse(stalled.get("unregistered"), "pressure must be active before revocation")

                mutation_attempted = True
                action_started, committed = test_action(base, run["runId"], action)
                closed = wait_stream(base, stream.stream_id,
                                     lambda state: state.get("unregistered") is True, timeout=5)
                fd_interval = wait_fd_closed(
                    pid, server_port, stream.client_port, stream.server_address, stream.client_address,
                    timeout=5, last_present_started_nanos=last_present,
                )
                assert_close_timing(self, base, closed, action_started, committed, fd_interval)

                status, _, _ = request(base, API + "/jobs", cookie=cookie)
                if expected_status is not None:
                    self.assertEqual(status, expected_status)
                else:
                    self.assertIn(status, (401, 403), "disabled account must fail closed on the REST queue")
                test_call(base, "success", resourceKey=f"sse-after-pressure-close:{uuid.uuid4()}")
            finally:
                try:
                    if stream is not None:
                        stream.reset()
                finally:
                    if mutation_attempted and restore and run is not None:
                        test_action(base, run["runId"], restore)

    def test_pr04_slow_reader_disconnect_does_not_block_real_worker(self) -> None:
        base = setting("YONA_BASE_URL")
        pid = int(setting("YONA_QUEUE_NODE_1_PID"))
        server_port = urlsplit(base).port or 80
        stream = SseSocketReader(base, setting("YONA_ADMIN_COOKIE"))
        try:
            self.assertEqual(stream.status, 200)
            last_present = wait_fd_open(
                pid, server_port, stream.client_port, stream.server_address, stream.client_address,
            )
            self.assertEqual(stream.read_frame()["event"], "reset")
            stalled = wait_saturated_stream(base, stream.stream_id)
            self.assertGreater(int(stalled["pressureBytesAttempted"]), 0)
            self.assertLessEqual(int(stalled["pressureBytesAttempted"]), PRESSURE_LIMIT)
            started = time.monotonic()
            run = test_call(base, "success", resourceKey=f"sse-worker-under-pressure:{uuid.uuid4()}")
            wait_job(base, run["jobIds"][0], "SUCCEEDED", timeout=5)
            self.assertLess(time.monotonic() - started, 5, "slow SSE writer must not consume queue worker progress")
            before_rst = metrics(base)["streams"][stream.stream_id]
            self.assertFalse(before_rst.get("unregistered"), "write-age cleanup must not preempt the RST exercise")
            stream.sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
            rst_started = time.monotonic_ns()
            stream.sock.close()
            deadline = rst_started + 2_000_000_000
            remaining = (deadline - time.monotonic_ns()) / 1_000_000_000
            self.assertGreater(remaining, 0, "client RST consumed the close deadline")
            wait_stream(
                base, stream.stream_id,
                lambda state: state.get("unregistered") is True, timeout=remaining,
            )
            remaining = (deadline - time.monotonic_ns()) / 1_000_000_000
            self.assertGreater(remaining, 0, "server unregistration consumed the real-FD close deadline")
            fd_interval = wait_fd_closed(
                pid, server_port, stream.client_port, stream.server_address, stream.client_address,
                timeout=remaining, last_present_started_nanos=last_present,
            )
            self.assertLessEqual(fd_interval[1] - rst_started, 2_000_000_000,
                                 "client RST must reach real server-FD closure within two seconds")
            pool = json.loads(request(base, TEST + "/pool", control=True)[2])
            self.assertLessEqual(int(pool["active"]), 1, "slow/disconnected SSE clients must not hold DB connections")
        finally:
            stream.reset()

    def test_pr04_silent_blocked_writer_watchdog_aborts_and_closes_within_two_seconds(self) -> None:
        base = setting("YONA_BASE_URL")
        pid = int(setting("YONA_QUEUE_NODE_1_PID"))
        server_port = urlsplit(base).port or 80
        stream = SseSocketReader(base, setting("YONA_ADMIN_COOKIE"))
        try:
            self.assertEqual(stream.status, 200)
            last_present = wait_fd_open(
                pid, server_port, stream.client_port, stream.server_address, stream.client_address,
            )
            self.assertEqual(stream.read_frame()["event"], "reset")
            stalled = wait_saturated_stream(base, stream.stream_id)
            self.assertGreater(int(stalled["pressureBytesAttempted"]), 0)
            self.assertLessEqual(int(stalled["pressureBytesAttempted"]), PRESSURE_LIMIT)
            oldest = int(stalled["firstOutstandingMonotonicNanos"])
            _, offset_upper, rtt, _ = clock_ping(base)
            self.assertLessEqual(rtt, 250_000_000, "clock-ping uncertainty is too large for the watchdog deadline")
            deadline = (oldest + 2_000_000_000 - offset_upper) / 1_000_000_000
            remaining = deadline - time.monotonic()
            self.assertGreater(remaining, 0, "silent stream exhausted its output-age close deadline before observation")
            closed = wait_stream(
                base, stream.stream_id,
                lambda state: state.get("unregistered") is True and
                int(state.get("abortRequestMonotonicNanos", 0)) > 0,
                timeout=remaining,
            )
            remaining = deadline - time.monotonic()
            self.assertGreater(remaining, 0, "watchdog unregister consumed the physical-close deadline")
            fd_interval = wait_fd_closed(
                pid, server_port, stream.client_port, stream.server_address, stream.client_address,
                timeout=remaining, last_present_started_nanos=last_present,
            )
            assert_close_timing(self, base, closed, oldest, oldest, fd_interval)
            self.assertEqual(int(closed.get("authorizationFailureMonotonicNanos", 0)), 0,
                             "the isolated watchdog check must not be caused by an authority mutation")
        finally:
            stream.reset()

    def test_pr04_drip_reader_progress_does_not_refresh_oldest_output_age(self) -> None:
        base = setting("YONA_BASE_URL")
        pid = int(setting("YONA_QUEUE_NODE_1_PID"))
        server_port = urlsplit(base).port or 80
        stream = SseSocketReader(base, setting("YONA_ADMIN_COOKIE"))
        try:
            self.assertEqual(stream.status, 200)
            last_present = wait_fd_open(
                pid, server_port, stream.client_port, stream.server_address, stream.client_address,
            )
            self.assertEqual(stream.read_frame()["event"], "reset")
            initial = wait_saturated_stream(base, stream.stream_id)
            self.assertGreater(int(initial["pressureBytesAttempted"]), 0)
            self.assertLessEqual(int(initial["pressureBytesAttempted"]), PRESSURE_LIMIT)
            oldest = int(initial["firstOutstandingMonotonicNanos"])
            progress_before = int(initial["transportProgressCount"])
            dripped_bytes = 0
            deadline = time.monotonic() + 2.3
            observed: dict[str, object] = initial
            while time.monotonic() < deadline and not observed.get("unregistered"):
                try:
                    dripped_bytes += len(stream.drip_some(1024))
                except (EOFError, ConnectionResetError, ConnectionAbortedError):
                    break
                time.sleep(0.1)
                observed = metrics(base)["streams"][stream.stream_id]
                self.assertEqual(int(observed["firstOutstandingMonotonicNanos"]), oldest,
                                 "partial progress must not reset the oldest outstanding output age")
                if not observed.get("unregistered"):
                    self.assertGreater(int(observed.get("pendingOutputBytes", 0)), 0,
                                       "the pressure producer must keep one bounded comment pending")
            self.assertGreater(dripped_bytes, 0, "drip reader must consume real response bytes")
            closed = wait_stream(base, stream.stream_id,
                                 lambda state: state.get("unregistered") is True, timeout=2)
            self.assertGreater(int(closed["transportProgressCount"]), progress_before,
                               "drip reader must make real transport progress before age abort")
            fd_interval = wait_fd_closed(
                pid, server_port, stream.client_port, stream.server_address, stream.client_address,
                timeout=2, last_present_started_nanos=last_present,
            )
            assert_close_timing(self, base, closed, oldest, oldest, fd_interval)
        finally:
            stream.reset()

    def test_pr04_z_node_shutdown_drains_active_sse_before_restart(self) -> None:
        base = setting("YONA_NODE_2_BASE_URL")
        pid = int(setting("YONA_QUEUE_NODE_2_PID"))
        server_port = urlsplit(base).port or 80
        node_environment = json.loads(setting("YONA_QUEUE_NODE_2_ENV_JSON"))
        control_dir = pathlib.Path(node_environment["YONA_QUEUE_ACCEPTANCE_CONTROL_DIR"])
        marker = control_dir / "sse-lifecycle" / f"node-{pid}-context-closed.json"
        self.assertFalse(marker.exists(), "shutdown observation must not reuse a stale record")

        before = lifecycle_state(base)
        self.assertEqual(int(before["tomcatInProgressAsyncCount"]), 0,
                         "the isolated node must begin with no async requests")
        cookie = new_session(base, "node-two-admin")
        connection, response = open_sse(base, cookie)
        client_port = connection.queue_client_port
        try:
            self.assertEqual(response.status, 200)
            self.assertEqual(response.headers.get("Connection", "").lower(), "close")
            self.assertIsNone(response.headers.get("Content-Encoding"))
            self.assertEqual(read_frame(response)["event"], "reset")
            last_present = wait_fd_open(
                pid, server_port, client_port, connection.queue_server_address,
                connection.queue_client_address,
            )
            active = lifecycle_state(base)
            self.assertEqual(int(active["activeSseStreams"]), 1)
            self.assertEqual(int(active["tomcatInProgressAsyncCount"]), 1,
                             "the actual Tomcat async count must include the live SSE request")
            self.assertTrue(listener_open(pid, server_port))

            os.kill(pid, signal.SIGTERM)
            record = wait_lifecycle_marker(marker)
            self.assertEqual(int(record["serverPid"]), pid)
            self.assertEqual(int(record["before"]["activeSseStreams"]), 1)
            self.assertEqual(int(record["before"]["tomcatInProgressAsyncCount"]), 1)
            self.assertEqual(int(record["after"]["activeSseStreams"]), 0)
            self.assertEqual(int(record["after"]["tomcatInProgressAsyncCount"]), 0,
                             "Ordered(1) observer must see actual Tomcat async count zero before server stop")
            self.assertTrue(record["after"]["admissionStopped"])
            self.assertLessEqual(int(record["contextClosedEventMonotonicNanos"]),
                                 int(record["after"]["tomcatAsyncZeroObservedMonotonicNanos"]))
            self.assertLessEqual(int(record["after"]["tomcatAsyncZeroObservedMonotonicNanos"]),
                                 int(record["after"]["afterListenerMonotonicNanos"]))

            wait_fd_closed(
                pid, server_port, client_port, connection.queue_server_address,
                connection.queue_client_address, timeout=10,
                last_present_started_nanos=last_present,
            )
            wait_listener_closed(pid, server_port, timeout=30)
            self.assertFalse(node2_health(base), "Tomcat server must stop after the close-listener record")
        finally:
            reset_sse_connection(connection, response)

        replacement = restart_node2(base)
        try:
            restarted_cookie = new_session(base, "node-two-admin")
            connection, response = open_sse(base, restarted_cookie, last_event_id="3021")
            try:
                self.assertEqual(response.status, 200)
                reset = read_frame(response)
                self.assertEqual(reset["event"], "reset")
                self.assertIn(json.loads(str(reset["data"]))["reason"], {"connect", "reconnect"})
            finally:
                reset_sse_connection(connection, response)
        finally:
            stop_restarted_node(replacement)




if __name__ == "__main__":
    unittest.main(verbosity=2)
