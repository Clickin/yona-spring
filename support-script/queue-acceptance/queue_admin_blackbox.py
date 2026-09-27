#!/usr/bin/env python3
"""Stdlib black-box acceptance for the administrator HTTP contract.

Run through run.py against disposable nodes with test-only routes, seeded
role-specific sessions, and real queue workers. Fixture endpoints must never
be present on the production classpath.
"""
from __future__ import annotations

import datetime as dt
import base64
import json
import os
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid

API = "/api/admin/queue/v1"
PAGE = "/site/admin/queue"
TEST = "/__test__/queue/v1"


def env(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        raise RuntimeError(f"required acceptance variable {name} is not set")
    return value


def url(path: str, base: str) -> str:
    return base.rstrip("/") + "/" + path.lstrip("/")


def request(base: str, path: str, *, method: str = "GET", body: object | None = None,
            cookie: str | None = None, csrf: str | None = None,
            test_key: str | None = None, authorization: str | None = None,
            yona_token: str | None = None, timeout: float = 10) -> tuple[int, object, bytes]:
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        data = json.dumps(body, separators=(",", ":")).encode()
        headers["Content-Type"] = "application/json"
    if cookie:
        headers["Cookie"] = cookie
    if csrf is not None:
        headers["X-XSRF-TOKEN"] = csrf
    if authorization is not None:
        headers["Authorization"] = authorization
    if yona_token is not None:
        headers["Yona-Token"] = yona_token
    if test_key:
        headers["X-Yona-Queue-Test-Control"] = test_key
    req = urllib.request.Request(url(path, base), data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read()
            parsed = json.loads(raw) if "json" in response.headers.get("Content-Type", "") else None
            return response.status, (response.headers, parsed), raw
    except urllib.error.HTTPError as response:
        with response:
            raw = response.read()
            try:
                parsed = json.loads(raw) if "json" in response.headers.get("Content-Type", "") else None
            except json.JSONDecodeError:
                parsed = None
            return response.code, (response.headers, parsed), raw


def call_test(scenario: str, *, cookie: str | None = None, **options: object) -> dict[str, object]:
    base = env("YONA_BASE_URL")
    body = {"scenario": scenario, "runKey": str(uuid.uuid4()), **options}
    status, _, raw = request(base, TEST + "/runs", method="POST", body=body,
                             cookie=cookie, test_key=env("YONA_QUEUE_TEST_CONTROL_TOKEN"))
    if status != 200:
        raise AssertionError(f"test harness returned HTTP {status}: {raw[:500]!r}")
    return json.loads(raw)


def observe(run_id: str) -> dict[str, object]:
    status, _, raw = request(env("YONA_BASE_URL"), TEST + f"/runs/{run_id}",
                             test_key=env("YONA_QUEUE_TEST_CONTROL_TOKEN"))
    if status != 200:
        raise AssertionError(f"fixture observation returned HTTP {status}: {raw[:500]!r}")
    return json.loads(raw)


def test_action(run_id: str, action: str) -> None:
    status, _, raw = request(env("YONA_BASE_URL"), TEST + f"/runs/{run_id}/actions/{action}",
                             method="POST", body={}, test_key=env("YONA_QUEUE_TEST_CONTROL_TOKEN"))
    if status != 204:
        raise AssertionError(f"test action {action} returned HTTP {status}: {raw[:500]!r}")


def job(job_id: str, base: str | None = None, cookie: str | None = None) -> dict[str, object]:
    status, _, raw = request(base or env("YONA_BASE_URL"), API + f"/jobs/{job_id}",
                             cookie=cookie or env("YONA_ADMIN_COOKIE"))
    if status != 200:
        raise AssertionError(f"job detail returned HTTP {status}: {raw[:500]!r}")
    return json.loads(raw)


def wait_for(job_id: str, predicate, timeout: float = 30) -> dict[str, object]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = job(job_id)
        if predicate(value):
            return value
        time.sleep(0.1)
    raise AssertionError(f"job {job_id} did not reach expected observable state")


def utc(value: str) -> dt.datetime:
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))


def open_sse(cookie: str, timeout: float = 8):
    req = urllib.request.Request(url(API + "/events", env("YONA_BASE_URL")),
                                 headers={"Accept": "text/event-stream", "Cookie": cookie})
    response = urllib.request.urlopen(req, timeout=timeout)
    if not response.headers.get("Content-Type", "").startswith("text/event-stream"):
        response.close()
        raise AssertionError("queue SSE did not return text/event-stream")
    return response


def read_sse_event(response, timeout: float = 8) -> tuple[str, str]:
    deadline = time.monotonic() + timeout
    event, data = "message", ""
    while time.monotonic() < deadline:
        line = response.readline()
        if line == b"":
            break
        if line.startswith(b"event:"):
            event = line[6:].strip().decode("utf-8")
        elif line.startswith(b"data:"):
            data = line[5:].strip().decode("utf-8")
        elif line in (bytes((10,)), bytes((13, 10))) and (event != "message" or data):
            return event, data
    raise AssertionError("no complete SSE event received")


class QueueAdminBlackBox(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        for required in ("YONA_BASE_URL", "YONA_ADMIN_COOKIE", "YONA_MEMBER_COOKIE",
                         "YONA_ORG_ADMIN_COOKIE", "YONA_PRE2FA_ADMIN_COOKIE",
                         "YONA_REVOKE_TEST_ADMIN_COOKIE", "YONA_CSRF_TOKEN", "YONA_MEMBER_CSRF_TOKEN",
                         "YONA_ORG_ADMIN_CSRF_TOKEN", "YONA_QUEUE_TEST_CONTROL_TOKEN"):
            env(required)

    def test_pr03_anonymous_pre2fa_and_non_site_roles_are_denied_from_rest(self) -> None:
        base = env("YONA_BASE_URL")
        status, (headers, payload), _ = request(base, API + "/jobs")
        self.assertEqual(status, 401)
        self.assertIsNone(headers.get("Location"), "JSON API must not redirect to HTML login")
        self.assertIsInstance(payload, dict)

        status, (headers, payload), _ = request(
            base, API + "/jobs", cookie=env("YONA_PRE2FA_ADMIN_COOKIE"))
        self.assertEqual(status, 403)
        self.assertIsNone(headers.get("Location"), "pre-2FA must not reach the HTML redirect gate")
        self.assertIsInstance(payload, dict)

        for cookie in (env("YONA_MEMBER_COOKIE"), env("YONA_ORG_ADMIN_COOKIE")):
            status, _, _ = request(base, API + "/jobs", cookie=cookie)
            self.assertEqual(status, 403)

    def test_pr04_anonymous_and_pre2fa_are_denied_from_sse(self) -> None:
        base = env("YONA_BASE_URL")
        status, (headers, payload), _ = request(base, API + "/events")
        self.assertEqual(status, 401)
        self.assertIsNone(headers.get("Location"))
        self.assertIsInstance(payload, dict)
        status, (headers, payload), _ = request(
            base, API + "/events", cookie=env("YONA_PRE2FA_ADMIN_COOKIE"))
        self.assertEqual(status, 403)
        self.assertIsNone(headers.get("Location"))
        self.assertIsInstance(payload, dict)

    def test_pr05_non_site_roles_are_denied_from_page(self) -> None:
        base = env("YONA_BASE_URL")
        for cookie in (env("YONA_MEMBER_COOKIE"), env("YONA_ORG_ADMIN_COOKIE")):
            status, _, raw = request(base, PAGE, cookie=cookie)
            self.assertEqual(status, 403)
            self.assertNotIn(b'data-testid="queue-admin-app"', raw)

    def test_pr03_non_site_roles_are_denied_on_rest_surfaces_without_effects(self) -> None:
        base = env("YONA_BASE_URL")
        roles = (
            (env("YONA_MEMBER_COOKIE"), env("YONA_MEMBER_CSRF_TOKEN")),
            (env("YONA_ORG_ADMIN_COOKIE"), env("YONA_ORG_ADMIN_CSRF_TOKEN")),
        )
        queued = call_test("delayed", delayMs=60000, resourceKey=f"deny-cancel:{uuid.uuid4()}")
        queued_id = queued["jobIds"][0]
        failed = call_test("manual-retry-permanent", resourceKey=f"deny-retry:{uuid.uuid4()}")
        wait_for(failed["jobIds"][0], lambda item: item["status"] == "FAILED")
        before_attempts = int(job(failed["jobIds"][0])["attemptCount"])
        blob = call_test("result-blob", resourceKey=f"deny-result:{uuid.uuid4()}")
        wait_for(blob["jobIds"][0], lambda item: item["status"] == "SUCCEEDED")
        admin_status, (admin_headers, _), expected_result = request(
            base, API + f"/jobs/{blob['jobIds'][0]}/result", cookie=env("YONA_ADMIN_COOKIE"))
        self.assertEqual(admin_status, 200)
        self.assertEqual(admin_headers.get("Content-Type"), "application/octet-stream")

        for cookie, csrf in roles:
            for path in (API + "/jobs", API + f"/jobs/{queued_id}"):
                status, _, _ = request(base, path, cookie=cookie)
                self.assertEqual(status, 403, path)

            command = {"commandId": str(uuid.uuid4()), "reason": "role denial must not mutate"}
            status, _, _ = request(base, API + f"/jobs/{queued_id}/cancel", method="POST",
                                   body=command, cookie=cookie, csrf=csrf)
            self.assertEqual(status, 403)
            self.assertEqual(job(queued_id)["status"], "QUEUED")
            self.assertEqual(observe(queued["runId"])["adminAuditCount"], 0)

            retry = {"commandId": str(uuid.uuid4()), "recoveryAcknowledged": False}
            status, _, _ = request(base, API + f"/jobs/{failed['jobIds'][0]}/retry", method="POST",
                                   body=retry, cookie=cookie, csrf=csrf)
            self.assertEqual(status, 403)
            self.assertEqual(job(failed["jobIds"][0])["status"], "FAILED")
            self.assertEqual(int(job(failed["jobIds"][0])["attemptCount"]), before_attempts)
            self.assertEqual(observe(failed["runId"])["adminAuditCount"], 0)

            status, (headers, _), raw = request(
                base, API + f"/jobs/{blob['jobIds'][0]}/result", cookie=cookie)
            self.assertEqual(status, 403)
            self.assertNotIn(expected_result, raw)
            self.assertNotEqual(headers.get("Content-Type"), "application/octet-stream")
            self.assertEqual(observe(blob["runId"])["adminAuditCount"], 0)

    def test_pr04_non_site_roles_are_denied_from_sse(self) -> None:
        base = env("YONA_BASE_URL")
        for cookie in (env("YONA_MEMBER_COOKIE"), env("YONA_ORG_ADMIN_COOKIE")):
            status, _, _ = request(base, API + "/events", cookie=cookie)
            self.assertEqual(status, 403)

    def test_pr03_filters_and_page_size_are_bounded(self) -> None:
        base = env("YONA_BASE_URL")
        status, _, _ = request(base, API + "/jobs?limit=101", cookie=env("YONA_ADMIN_COOKIE"))
        self.assertEqual(status, 400)
        status, _, _ = request(base, API + "/jobs?status=NOT_A_STATUS", cookie=env("YONA_ADMIN_COOKIE"))
        self.assertEqual(status, 400)

    def test_pr03_csrf_token_must_match_not_merely_be_present(self) -> None:
        base = env("YONA_BASE_URL")
        run = call_test("delayed", delayMs=30000, resourceKey=f"csrf:{uuid.uuid4()}")
        job_id = run["jobIds"][0]
        command = {"commandId": str(uuid.uuid4()), "reason": "invalid token must not cancel"}
        for token in (None, "definitely-not-the-session-token"):
            status, _, _ = request(base, API + f"/jobs/{job_id}/cancel", method="POST", body=command,
                                   cookie=env("YONA_ADMIN_COOKIE"), csrf=token)
            self.assertEqual(status, 403)
            self.assertEqual(job(job_id)["status"], "QUEUED")
        for token_header in ({"authorization": "token intentionally-not-a-valid-token"},
                             {"yona_token": "intentionally-not-a-valid-token"}):
            status, _, _ = request(base, API + f"/jobs/{job_id}/cancel", method="POST", body=command,
                                   cookie=env("YONA_ADMIN_COOKIE"), **token_header)
            self.assertEqual(status, 403)
            self.assertEqual(job(job_id)["status"], "QUEUED")
            self.assertEqual(observe(run["runId"])["adminAuditCount"], 0)
        status, _, raw = request(base, API + f"/jobs/{job_id}/cancel", method="POST", body=command,
                                 cookie=env("YONA_ADMIN_COOKIE"), csrf=env("YONA_CSRF_TOKEN"))
        self.assertIn(status, (200, 202))
        self.assertTrue(json.loads(raw)["changed"])
        replay_status, _, replay_raw = request(base, API + f"/jobs/{job_id}/cancel", method="POST", body=command,
                                               cookie=env("YONA_ADMIN_COOKIE"), csrf=env("YONA_CSRF_TOKEN"))
        self.assertIn(replay_status, (200, 202))
        self.assertFalse(json.loads(replay_raw)["changed"])
        self.assertEqual(job(job_id)["status"], "CANCELLED")

    def test_pr03_site_admin_authority_is_rechecked_for_each_rest_request(self) -> None:
        base = env("YONA_BASE_URL")
        cookie = env("YONA_REVOKE_TEST_ADMIN_COOKIE")
        run = call_test("success", cookie=cookie, resourceKey=f"fresh-role:{uuid.uuid4()}")
        job_id = run["jobIds"][0]
        wait_for(job_id, lambda item: item["status"] == "SUCCEEDED")
        status, _, _ = request(base, API + "/jobs", cookie=cookie)
        self.assertEqual(status, 200)
        try:
            test_action(run["runId"], "revoke-test-manager")
            status, (headers, payload), _ = request(base, API + "/jobs", cookie=cookie)
            self.assertEqual(status, 403)
            self.assertIsNone(headers.get("Location"))
            self.assertIsInstance(payload, dict)
        finally:
            test_action(run["runId"], "restore-test-manager")

    def test_pr03_business_change_and_enqueue_share_one_transaction(self) -> None:
        committed = call_test("enqueue-commit", businessMarker=f"commit-{uuid.uuid4()}")
        self.assertEqual(committed["enqueueOutcome"], "COMMITTED")
        self.assertEqual(len(committed["jobIds"]), 1)
        self.assertTrue(committed["committedBusinessMarker"])
        self.assertEqual(wait_for(committed["jobIds"][0], lambda j: j["status"] == "SUCCEEDED")["status"], "SUCCEEDED")

        rolled_back = call_test("enqueue-rollback", businessMarker=f"rollback-{uuid.uuid4()}")
        self.assertEqual(rolled_back["enqueueOutcome"], "ROLLED_BACK")
        self.assertEqual(rolled_back["jobIds"], [])
        self.assertFalse(rolled_back["committedBusinessMarker"])

    def test_pr03_retryable_failure_is_retained_and_scheduled_by_persisted_due_time(self) -> None:
        run = call_test("retry-once", resourceKey=f"retry:{uuid.uuid4()}")
        retry_wait = wait_for(run["jobIds"][0], lambda j: j["status"] == "RETRY_WAIT")
        self.assertEqual(retry_wait["attempts"][0]["status"], "RETRYABLE_FAILURE")
        self.assertIsNotNone(retry_wait["nextAttemptAt"])
        failed_attempt = retry_wait["attempts"][0]
        persisted_due = utc(retry_wait["nextAttemptAt"])
        delay = (persisted_due - utc(failed_attempt["finishedAt"])).total_seconds()
        self.assertGreaterEqual(delay, 2.5)
        self.assertLessEqual(delay, 5.0)

        result = wait_for(run["jobIds"][0], lambda j: j["status"] == "SUCCEEDED", timeout=45)
        attempts = result["attempts"]
        self.assertEqual(len(attempts), 2)
        self.assertEqual(attempts[0]["status"], "SUCCEEDED")
        self.assertEqual(attempts[1]["status"], "RETRYABLE_FAILURE")
        self.assertIsNone(result["failureDisposition"])
        self.assertGreaterEqual(utc(attempts[0]["startedAt"]), persisted_due)

    def test_pr03_permanent_failure_does_not_retry_and_manual_retry_is_audited(self) -> None:
        run = call_test("manual-retry", resourceKey=f"manual:{uuid.uuid4()}")
        failed = wait_for(run["jobIds"][0], lambda j: j["status"] == "FAILED")
        self.assertEqual(failed["failureDisposition"], "PERMANENT")
        first_attempt_count = len(failed["attempts"])
        time.sleep(2)
        self.assertEqual(len(job(run["jobIds"][0])["attempts"]), first_attempt_count)
        command_id = str(uuid.uuid4())
        body = {"commandId": command_id, "recoveryAcknowledged": False, "reason": "fix applied in test handler"}
        status, _, retry_raw = request(env("YONA_BASE_URL"), API + f"/jobs/{run['jobIds'][0]}/retry",
                                       method="POST", body=body, cookie=env("YONA_ADMIN_COOKIE"), csrf=env("YONA_CSRF_TOKEN"))
        self.assertEqual(status, 202)
        replay_status, _, replay_raw = request(env("YONA_BASE_URL"), API + f"/jobs/{run['jobIds'][0]}/retry",
                                                method="POST", body=body, cookie=env("YONA_ADMIN_COOKIE"), csrf=env("YONA_CSRF_TOKEN"))
        self.assertIn(replay_status, (200, 202))
        self.assertFalse(json.loads(replay_raw)["changed"])
        succeeded = wait_for(run["jobIds"][0], lambda j: j["status"] == "SUCCEEDED")
        self.assertEqual(len(succeeded["attempts"]), first_attempt_count + 1)
        self.assertEqual(succeeded["attempts"][0]["status"], "SUCCEEDED")


    def test_pr03_unknown_type_and_payload_version_are_preserved_not_run(self) -> None:
        for scenario in ("unsupported-type", "unsupported-payload-version"):
            run = call_test(scenario, payloadVersion=987)
            result = wait_for(run["jobIds"][0], lambda j: j["status"] == "BLOCKED_UNSUPPORTED")
            self.assertEqual(int(result["attemptCount"]), 0)
            self.assertEqual(len(result["attempts"]), 0)
            self.assertTrue(observe(run["runId"])["effects"] == [])

    def test_pr03_due_time_is_not_early_and_ids_are_safe_json_strings(self) -> None:
        delayed = call_test("delayed", delayMs=3000, resourceKey=f"delay:{uuid.uuid4()}")
        result = wait_for(delayed["jobIds"][0], lambda j: int(j["attemptCount"]) >= 1)
        self.assertGreaterEqual(utc(result["startedAt"]), utc(result["scheduledAt"]))
        large = call_test("large-id", resourceKey=f"id:{uuid.uuid4()}")
        job_id = large["jobIds"][0]
        self.assertIsInstance(job_id, str)
        self.assertGreater(int(job_id), 9007199254740991)
        self.assertEqual(job(job_id)["id"], job_id)

    def test_pr03_idempotency_uses_exact_payload_bytes_not_json_equivalence(self) -> None:
        key = f"same-key:{uuid.uuid4()}"
        payload = b'{"left":1,"right":"x"}'
        same_bytes = base64.b64encode(payload).decode("ascii")
        equivalent_bytes = base64.b64encode(b'{ "right" : "x", "left" : 1 }').decode("ascii")
        first = call_test("idempotent", idempotencyKey=key, payloadBytesBase64=same_bytes)
        second = call_test("idempotent", idempotencyKey=key, payloadBytesBase64=same_bytes)
        self.assertEqual(first["jobIds"], second["jobIds"])
        conflict = call_test("idempotent", idempotencyKey=key, payloadBytesBase64=equivalent_bytes)
        self.assertEqual(conflict["enqueueOutcome"], "IDEMPOTENCY_CONFLICT")
        version_conflict = call_test(
            "idempotent", idempotencyKey=key, payloadVersion=2, payloadBytesBase64=same_bytes)
        self.assertEqual(version_conflict["enqueueOutcome"], "IDEMPOTENCY_CONFLICT")

        unkeyed_a = call_test("idempotent", payloadBytesBase64=same_bytes)
        unkeyed_b = call_test("idempotent", payloadBytesBase64=same_bytes)
        self.assertNotEqual(unkeyed_a["jobIds"], unkeyed_b["jobIds"])

        pair = call_test("same-resource-pair", resourceKey=f"shared:{uuid.uuid4()}")
        run_id = pair["runId"]
        first_job, second_job = pair["jobIds"]
        wait_for(first_job, lambda j: j["status"] in ("RUNNING", "CANCEL_REQUESTED"))
        self.assertEqual(int(job(second_job)["attemptCount"]), 0)
        self.assertEqual(observe(run_id)["maxConcurrentForResource"], 1)
        test_action(run_id, "release-gate")
        wait_for(second_job, lambda j: j["status"] == "SUCCEEDED")
        self.assertEqual(observe(run_id)["maxConcurrentForResource"], 1)

    def test_pr03_attempt_history_is_bounded_per_response_and_fully_pageable(self) -> None:
        run = call_test("manual-retry-permanent", resourceKey=f"history:{uuid.uuid4()}")
        job_id = run["jobIds"][0]
        wait_for(job_id, lambda item: item["status"] == "FAILED")
        target_attempt_count = 105
        while int(job(job_id)["attemptCount"]) < target_attempt_count:
            current = job(job_id)
            command = {"commandId": str(uuid.uuid4()), "recoveryAcknowledged": False}
            status, _, raw = request(
                env("YONA_BASE_URL"), API + f"/jobs/{job_id}/retry", method="POST", body=command,
                cookie=env("YONA_ADMIN_COOKIE"), csrf=env("YONA_CSRF_TOKEN"))
            self.assertEqual(status, 202, raw[:500])
            next_count = int(current["attemptCount"]) + 1
            wait_for(job_id, lambda item, count=next_count:
                     item["status"] == "FAILED" and int(item["attemptCount"]) == count)

        cursor = None
        observed: list[str] = []
        previous_oldest = None
        while True:
            query = {"attemptLimit": "17"}
            if cursor is not None:
                query["attemptCursor"] = cursor
            path = API + f"/jobs/{job_id}?" + urllib.parse.urlencode(query)
            status, _, raw = request(env("YONA_BASE_URL"), path, cookie=env("YONA_ADMIN_COOKIE"))
            self.assertEqual(status, 200, raw[:500])
            detail = json.loads(raw)
            attempts = detail["attempts"]
            self.assertLessEqual(len(attempts), 17)
            self.assertEqual(detail["attemptHistoryCount"], str(target_attempt_count))
            numbers = [item["attemptNo"] for item in attempts]
            self.assertEqual(numbers, sorted(numbers, key=int, reverse=True))
            if previous_oldest is not None:
                self.assertLess(int(numbers[0]), int(previous_oldest))
            observed.extend(numbers)
            previous_oldest = numbers[-1] if numbers else previous_oldest
            cursor = detail["nextAttemptCursor"]
            if cursor is None:
                break

        self.assertEqual(len(observed), target_attempt_count)
        self.assertEqual(len(set(observed)), target_attempt_count)
        self.assertEqual({int(number) for number in observed}, set(range(1, target_attempt_count + 1)))
        status, _, raw = request(
            env("YONA_BASE_URL"), API + f"/jobs/{job_id}", cookie=env("YONA_ADMIN_COOKIE"))
        self.assertEqual(status, 200)
        self.assertLessEqual(len(json.loads(raw)["attempts"]), 50)

    def test_pr03_one_due_row_has_one_claim_across_two_real_instances(self) -> None:
        node_two = env("YONA_NODE_2_BASE_URL")
        run = call_test("simultaneous-claim", resourceKey=f"claim:{uuid.uuid4()}")
        job_id = run["jobIds"][0]
        running = wait_for(job_id, lambda item: item["status"] == "RUNNING")
        self.assertEqual(int(running["attemptCount"]), 1)
        other_instance = job(job_id, node_two, env("YONA_NODE_2_ADMIN_COOKIE"))
        self.assertEqual(other_instance["id"], job_id)
        self.assertEqual(int(other_instance["attemptCount"]), 1)
        self.assertEqual(observe(run["runId"])["taskStarts"], 1)
        test_action(run["runId"], "release-gate")
        finished = wait_for(job_id, lambda item: item["status"] == "SUCCEEDED")
        self.assertEqual(len(finished["attempts"]), 1)
        self.assertEqual(observe(run["runId"])["taskStarts"], 1)

    def test_pr03_cancel_request_does_not_claim_the_handler_stopped(self) -> None:
        resource = f"cancel:{uuid.uuid4()}"
        first = call_test("gated-cancel", resourceKey=resource)
        run_id, job_id = first["runId"], first["jobIds"][0]
        wait_for(job_id, lambda j: j["status"] == "RUNNING")
        command = {"commandId": str(uuid.uuid4()), "reason": "cooperative cancel test"}
        status, _, _ = request(env("YONA_BASE_URL"), API + f"/jobs/{job_id}/cancel", method="POST",
                               body=command, cookie=env("YONA_ADMIN_COOKIE"), csrf=env("YONA_CSRF_TOKEN"))
        self.assertEqual(status, 202)
        self.assertEqual(job(job_id)["status"], "CANCEL_REQUESTED")
        second = call_test("success", resourceKey=resource)
        time.sleep(0.5)
        self.assertEqual(int(job(second["jobIds"][0])["attemptCount"]), 0)
        test_action(run_id, "release-gate")
        wait_for(job_id, lambda j: j["status"] == "CANCELLED")
        self.assertEqual(wait_for(second["jobIds"][0], lambda j: j["status"] == "SUCCEEDED")["status"], "SUCCEEDED")

    def test_pr04_site_admin_role_disable_and_session_invalidation_close_live_streams(self) -> None:
        cases = [
            (env("YONA_REVOKE_TEST_ADMIN_COOKIE"), "revoke-test-manager", "restore-test-manager"),
            (env("YONA_DISABLED_TEST_ADMIN_COOKIE"), "disable-test-manager", "restore-disabled-test-manager"),
            (env("YONA_SESSION_TEST_ADMIN_COOKIE"), "invalidate-test-manager-session", None),
        ]
        for cookie, action, restore in cases:
            run = call_test("success", cookie=cookie, resourceKey=f"revoke:{uuid.uuid4()}")
            wait_for(run["jobIds"][0], lambda j: j["status"] == "SUCCEEDED")
            stream = open_sse(cookie, timeout=1)
            try:
                self.assertEqual(read_sse_event(stream)[0], "reset")
                time.sleep(1.1)  # Drain any pre-connect generation invalidation.
                test_action(run["runId"], action)
                revoked_at = time.monotonic()
                deadline = revoked_at + 5
                while time.monotonic() < deadline:
                    try:
                        line = stream.readline()
                    except TimeoutError:
                        continue
                    if line == b"":
                        self.assertLessEqual(time.monotonic() - revoked_at, 5)
                        break
                    if line.startswith((b"event:", b"data:")):
                        self.fail(f"SSE sent an event after {action}: {line!r}")
                else:
                    self.fail(f"SSE remained open beyond five seconds after {action}")
            finally:
                stream.close()
                if restore:
                    test_action(run["runId"], restore)

    def test_pr04_sse_invalidates_after_commit_and_resets_on_connect(self) -> None:
        stream = open_sse(env("YONA_ADMIN_COOKIE"))
        try:
            self.assertEqual(read_sse_event(stream)[0], "reset")
            before = call_test("success", resourceKey=f"sse:{uuid.uuid4()}")
            event, data = read_sse_event(stream)
            self.assertEqual(event, "changed")
            self.assertGreaterEqual(int(json.loads(data)["generation"]), 1)
            self.assertEqual(wait_for(before["jobIds"][0], lambda j: j["status"] == "SUCCEEDED")["status"], "SUCCEEDED")
        finally:
            stream.close()

    def test_pr03_result_is_streamed_with_matching_digest(self) -> None:
        run = call_test("result-blob", resourceKey=f"result:{uuid.uuid4()}")
        result = wait_for(run["jobIds"][0], lambda j: j["status"] == "SUCCEEDED")
        status, (headers, _), raw = request(env("YONA_BASE_URL"), API + f"/jobs/{run['jobIds'][0]}/result",
                                            cookie=env("YONA_ADMIN_COOKIE"))
        self.assertEqual(status, 200)
        import hashlib
        self.assertEqual(headers.get("X-Content-SHA256"), hashlib.sha256(raw).hexdigest())
        self.assertGreater(int(result["result"]["sizeBytes"]), 0)

    def test_pr03_paused_old_attempt_cannot_commit_db_or_file_effect(self) -> None:
        run = call_test("stale-fence-file", resourceKey=f"repo:{uuid.uuid4()}")
        run_id, job_id = run["runId"], run["jobIds"][0]
        wait_for(job_id, lambda j: j["status"] == "RUNNING")
        test_action(run_id, "pause-old-attempt")
        test_action(run_id, "wait-for-lease-expiry")
        self.assertFalse(observe(run_id)["gateOpen"])
        test_action(run_id, "resume-old-attempt")
        old = observe(run_id)
        self.assertFalse(old["oldFenceWritesAccepted"])
        self.assertEqual(old["committedEffectCount"], 0)
        test_action(run_id, "release-gate")
        final = wait_for(job_id, lambda j: j["status"] == "SUCCEEDED", timeout=45)
        self.assertGreaterEqual(len(final["attempts"]), 2)
        self.assertEqual(observe(run_id)["committedEffectCount"], 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
