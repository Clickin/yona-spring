#!/usr/bin/env python3
"""Live MVC SSE acceptance using the real two-JVM queue fixture.

Checks application stream isolation/completion, not OS socket lifetime or
container-private async counters. Run the fixture with virtual threads both
false and true; the HTTP protocol is identical in either mode.
"""
from __future__ import annotations

import http.client
import gzip
import json
import os
import signal
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


def setting(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        raise RuntimeError(f"missing fixture setting {name}")
    return value


def request(base: str, path: str, *, method="GET", body=None, cookie=None, control=False):
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if cookie:
        headers["Cookie"] = cookie
    if control:
        headers["X-Yona-Queue-Test-Control"] = setting("YONA_QUEUE_TEST_CONTROL_TOKEN")
    req = urllib.request.Request(base.rstrip("/") + path, data=data, headers=headers, method=method)
    try:
        response = HTTP.open(req, timeout=10)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        return response.status, response.headers, response.read()

def error_body(response):
    data = response.read()
    if response.headers.get("Content-Encoding") == "gzip":
        data = gzip.decompress(data)
    return json.loads(data)



def new_session(base: str, role: str) -> str:
    status, _, raw = request(base, TEST + "/sessions", method="POST", body={"role": role}, control=True)
    if status != 200:
        raise AssertionError(f"fixture session failed: {status} {raw!r}")
    return json.loads(raw)["cookie"]


def metrics(base: str):
    status, _, raw = request(base, TEST + "/sse/metrics", control=True)
    if status != 200:
        raise AssertionError(f"SSE observer failed: {status} {raw!r}")
    return json.loads(raw)


def wait_stream(base, stream_id, predicate, timeout=2):
    deadline = time.monotonic() + timeout
    last = {}
    while time.monotonic() < deadline:
        last = metrics(base)["streams"].get(stream_id, {})
        if predicate(last):
            return last
        time.sleep(0.02)
    raise AssertionError(f"stream did not reach expected application state: {last}")


def open_sse(base, cookie=None, *, last_event_id=None, pressure=False, http10=False):
    parts = urlsplit(base)
    connection = http.client.HTTPConnection(parts.hostname, parts.port or 80, timeout=16)
    if http10:
        connection._http_vsn = 10
        connection._http_vsn_str = "HTTP/1.0"
    headers = {"Accept": "text/event-stream", "Accept-Encoding": "gzip"}
    if cookie:
        headers["Cookie"] = cookie
    if last_event_id is not None:
        headers["Last-Event-ID"] = last_event_id
    if pressure:
        headers["X-Yona-Queue-Test-Control"] = setting("YONA_QUEUE_TEST_CONTROL_TOKEN")
        headers["X-Yona-Queue-Test-Pressure"] = "bounded-comments"
    connection.connect()
    if pressure:
        connection.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
    connection.queue_reset_socket = connection.sock.dup()
    connection.request("GET", parts.path.rstrip("/") + API + "/events", headers=headers)
    return connection, connection.getresponse()


def close_streams(base, streams):
    ids = []
    for connection, response in streams:
        stream_id = response.headers.get("X-Yona-Queue-Test-Stream-ID")
        if response.status == 200 and stream_id:
            ids.append(stream_id)
        reset = connection.queue_reset_socket
        reset.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
        response.close()
        connection.close()
        reset.close()
    deadline = time.monotonic() + 16
    for stream_id in ids:
        wait_stream(base, stream_id, lambda state: not state.get("active", True),
                    timeout=max(0.1, deadline - time.monotonic()))


def read_frame(response, timeout=16):
    response.fp.raw._sock.settimeout(timeout)
    result = {"event": "message", "data": "", "comments": []}
    lines = []
    while True:
        line = response.readline()
        if not line:
            raise AssertionError("SSE ended before a complete frame")
        if line in (b"\n", b"\r\n"):
            if lines:
                break
        else:
            lines.append(line.rstrip(b"\r\n").decode())
    data = []
    for line in lines:
        if line.startswith(":"):
            result["comments"].append(line[1:].lstrip())
        elif line.startswith("event:"):
            result["event"] = line[6:].strip()
        elif line.startswith("data:"):
            data.append(line[5:].lstrip())
    result["data"] = "\n".join(data)
    return result


def run_job(base, cookie=None):
    status, _, raw = request(base, TEST + "/runs", method="POST", cookie=cookie, control=True,
                             body={"scenario": "success", "runKey": str(uuid.uuid4()),
                                   "resourceKey": "sse:" + str(uuid.uuid4())})
    if status != 200:
        raise AssertionError(f"queue scenario failed: {status} {raw!r}")
    run = json.loads(raw)
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        status, _, raw = request(base, API + "/jobs/" + run["jobIds"][0], cookie=setting("YONA_ADMIN_COOKIE"))
        if status == 200 and json.loads(raw)["status"] == "SUCCEEDED":
            return run
        time.sleep(0.05)
    raise AssertionError("queue worker did not finish")


def action(base, run_id, name):
    status, _, raw = request(base, TEST + f"/runs/{run_id}/actions/{name}",
                             method="POST", body={}, control=True)
    if status != 204:
        raise AssertionError(f"fixture mutation failed: {status} {raw!r}")


def watermark(base):
    status, _, raw = request(base, API + "/jobs?limit=1", cookie=setting("YONA_ADMIN_COOKIE"))
    if status != 200:
        raise AssertionError(f"queue snapshot failed: {status} {raw!r}")
    return int(json.loads(raw)["snapshotGeneration"])


def await_generation(response, generation, timeout=2):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        frame = read_frame(response, max(0.01, deadline - time.monotonic()))
        if frame["event"] == "changed":
            value = json.loads(frame["data"])["generation"]
            if not isinstance(value, str) or not value.isdecimal():
                raise AssertionError("generation must be a decimal string")
            if int(value) >= generation:
                return
    raise AssertionError("healthy SSE did not reach the REST watermark")


class SseAcceptance(unittest.TestCase):
    def setUp(self):
        self.base = setting("YONA_BASE_URL")

    def test_authentication_and_http10_are_transport_independent(self):
        for cookie, expected in ((None, 401), (setting("YONA_MEMBER_COOKIE"), 403),
                                 (setting("YONA_ADMIN_COOKIE"), 200)):
            pair = open_sse(self.base, cookie, http10=True)
            try:
                response = pair[1]
                self.assertEqual(expected, response.status)
                if expected == 200:
                    self.assertEqual("reset", read_frame(response)["event"])
                else:
                    self.assertIn("application/json", response.headers.get("Content-Type", ""))
                    self.assertIsNone(response.headers.get("Location"))
                    self.assertIn("code", error_body(response))
            finally:
                close_streams(self.base, [pair])

    def test_reset_reconnect_heartbeat_and_committed_changes(self):
        for cursor in (None, "not-a-replay-cursor"):
            pair = open_sse(self.base, setting("YONA_ADMIN_COOKIE"), last_event_id=cursor)
            try:
                response = pair[1]
                self.assertEqual(200, response.status)
                self.assertEqual("no-cache", response.headers.get("Cache-Control"))
                self.assertEqual("no", response.headers.get("X-Accel-Buffering"))
                self.assertNotEqual("close", response.headers.get("Connection", "").lower())
                self.assertIsNone(response.headers.get("Content-Encoding"))
                self.assertIsNone(response.headers.get("Content-Length"))
                first = read_frame(response)
                self.assertEqual("reset", first["event"])
                self.assertEqual("reconnect" if cursor else "connect", json.loads(first["data"])["reason"])
                run_job(self.base)
                await_generation(response, watermark(self.base))
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline:
                    frame = read_frame(response, max(0.01, deadline - time.monotonic()))
                    if "heartbeat" in frame["comments"]:
                        break
                else:
                    self.fail("no comment heartbeat within 15 seconds")
            finally:
                close_streams(self.base, [pair])

    def test_session_principal_and_node_limits_return_json_before_sse(self):
        def rejected(cookie):
            pair = open_sse(self.base, cookie)
            try:
                response = pair[1]
                self.assertEqual(429, response.status)
                self.assertEqual("1", response.headers.get("Retry-After"))
                self.assertIn("application/json", response.headers.get("Content-Type", ""))
                self.assertEqual("RATE_LIMITED", error_body(response)["code"])
            finally:
                close_streams(self.base, [pair])
        cookie = new_session(self.base, "admin")
        for cookies in ((cookie, cookie), (cookie, new_session(self.base, "admin"))):
            streams = []
            try:
                for value in cookies:
                    streams.append(open_sse(self.base, value))
                    self.assertEqual(200, streams[-1][1].status)
                rejected(cookie)
            finally:
                close_streams(self.base, streams)
        streams = []
        try:
            for index in range(32):
                streams.append(open_sse(self.base, new_session(self.base, f"capacity-admin-{index}")))
                self.assertEqual(200, streams[-1][1].status)
            rejected(new_session(self.base, "capacity-admin-32"))
            other = setting("YONA_NODE_2_BASE_URL")
            pair = open_sse(other, new_session(other, "node-two-admin"))
            try:
                self.assertEqual(200, pair[1].status)
            finally:
                close_streams(other, [pair])
        finally:
            close_streams(self.base, streams)

    def test_shared_polling_does_not_hold_stream_database_connections(self):
        streams = []
        try:
            for role in ("admin", "revoke-admin"):
                cookie = new_session(self.base, role)
                streams.extend(open_sse(self.base, cookie) for _ in range(2))
            before = metrics(self.base)
            pool = json.loads(request(self.base, TEST + "/pool", control=True)[2])
            time.sleep(6.2)
            after = metrics(self.base)
            polls = after["generationPollCount"] - before["generationPollCount"]
            self.assertGreaterEqual(polls, 5)
            self.assertLessEqual(polls, 8)
            for key in ("principalAuthCheckCount", "sessionValidityCheckCount"):
                self.assertGreaterEqual(after[key] - before[key], 10)
                self.assertLessEqual(after[key] - before[key], 16)
            later = json.loads(request(self.base, TEST + "/pool", control=True)[2])
            self.assertLessEqual(later["active"], pool["active"] + 1)
        finally:
            close_streams(self.base, streams)

    def test_revocation_disable_and_session_invalidation_complete_stream(self):
        for role, revoke, restore in (("revoke-admin", "revoke-test-manager", "restore-test-manager"),
                                      ("disabled-admin", "disable-test-manager", "restore-disabled-test-manager"),
                                      ("session-admin", "invalidate-test-manager-session", None)):
            cookie = new_session(self.base, role)
            run = run_job(self.base, cookie)
            pair = open_sse(self.base, cookie)
            try:
                response = pair[1]
                self.assertEqual("reset", read_frame(response)["event"])
                action(self.base, run["runId"], revoke)
                response.fp.raw._sock.settimeout(2)
                response.read()  # Drain already-in-flight invalidations and observe application EOF.
                wait_stream(self.base, response.headers["X-Yona-Queue-Test-Stream-ID"],
                            lambda state: state.get("active") is False)
                status = request(self.base, API + "/jobs", cookie=cookie)[0]
                self.assertIn(status, (401, 403))
            finally:
                close_streams(self.base, [pair])
                if restore:
                    action(self.base, run["runId"], restore)

    def test_nonreading_socket_is_isolated_without_blocking_other_stream_or_worker(self):
        slow = open_sse(self.base, new_session(self.base, "capacity-admin-0"), pressure=True)
        healthy = open_sse(self.base, new_session(self.base, "capacity-admin-1"))
        try:
            self.assertEqual("reset", read_frame(healthy[1])["event"])
            stream_id = slow[1].headers["X-Yona-Queue-Test-Stream-ID"]
            wait_stream(self.base, stream_id,
                        lambda state: state.get("pressureStartedNanos", 0) > 0 and
                        state.get("pressureFinishedNanos") == 0)
            run_job(self.base)
            await_generation(healthy[1], watermark(self.base), timeout=1)
            wait_stream(self.base, stream_id, lambda state: state.get("active") is False, timeout=2)
            run_job(self.base)
            await_generation(healthy[1], watermark(self.base), timeout=1)
        finally:
            close_streams(self.base, [slow, healthy])

    def test_z_shutdown_completes_all_healthy_streams(self):
        base = setting("YONA_NODE_2_BASE_URL")
        streams = [open_sse(base, new_session(base, "node-two-admin")) for _ in range(2)]
        try:
            for _, response in streams:
                self.assertEqual("reset", read_frame(response)["event"])
            os.kill(int(setting("YONA_QUEUE_NODE_2_PID")), signal.SIGTERM)
            for _, response in streams:
                response.fp.raw._sock.settimeout(5)
                response.read()
        finally:
            for connection, response in streams:
                response.close()
                connection.close()
                connection.queue_reset_socket.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
