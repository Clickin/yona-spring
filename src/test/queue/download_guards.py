import hashlib
import json
from pathlib import Path
import os
import time
import urllib.error
import urllib.request
import uuid

settings = os.environ
queue_root = Path(settings['YONA_QUEUE_DATA_DIR'])
root = queue_root.parent
base = settings['YONA_BASE_URL']

def call(path, body=None, control=False):
    headers = {'Cookie': settings['YONA_ADMIN_COOKIE']}
    if control:
        headers['X-Yona-Queue-Test-Control'] = settings['YONA_QUEUE_TEST_CONTROL_TOKEN']
    if body is not None:
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        response = urllib.request.urlopen(request, timeout=20)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        return response.status, dict(response.headers), response.read()

status, _, raw = call('/__test__/queue/v1/runs', {'scenario': 'result-blob', 'runKey': str(uuid.uuid4())}, True)
assert status == 200, (status, raw)
run = json.loads(raw)
job_id = run['jobIds'][0]
path = '/api/admin/queue/v1/jobs/' + job_id
for _ in range(200):
    status, _, raw = call(path)
    assert status == 200, (status, raw)
    if json.loads(raw)['status'] == 'SUCCEEDED':
        break
    time.sleep(0.05)
else:
    raise AssertionError('Real artifact producer did not complete')
expected = ('queue-result-' + run['runId']).encode()
result_path = path + '/result'
files = list((queue_root / 'artifacts' / job_id).rglob(run['runId'] + '.bin'))
assert len(files) == 1, files
artifact = files[0]

def valid():
    status, headers, body = call(result_path)
    assert status == 200 and body == expected, (status, body)
    digest = next(value for key, value in headers.items() if key.lower() == 'x-content-sha256')
    assert digest == hashlib.sha256(expected).hexdigest()

def rejected(expected_status):
    status, headers, body = call(result_path)
    assert status == expected_status, (status, body)
    assert json.loads(body)['code'] in {'RESULT_CONFLICT', 'NOT_FOUND'}
    assert not any(key.lower() in {'content-disposition', 'x-content-sha256'} for key in headers)
    assert expected not in body

valid()
try:
    artifact.write_bytes(b'X' + expected[1:])
    rejected(409)
finally:
    artifact.write_bytes(expected)
valid()
backup = artifact.with_name(artifact.name + '.guard-backup')
outside = root / 'outside-matching-artifact.bin'
outside.write_bytes(expected)
try:
    artifact.rename(backup)
    try:
        artifact.symlink_to(outside)
        rejected(409)
    finally:
        artifact.unlink(missing_ok=True)
        backup.rename(artifact)
finally:
    outside.unlink(missing_ok=True)
valid()
parent = artifact.parent
parent_backup = parent.with_name(parent.name + '.guard-backup')
outside_directory = root / 'outside-artifact-directory'
outside_directory.mkdir()
(outside_directory / artifact.name).write_bytes(expected)
try:
    parent.rename(parent_backup)
    try:
        parent.symlink_to(outside_directory, target_is_directory=True)
        rejected(409)
    finally:
        parent.unlink(missing_ok=True)
        parent_backup.rename(parent)
finally:
    (outside_directory / artifact.name).unlink()
    outside_directory.rmdir()
valid()
artifact.rename(backup)
try:
    rejected(404)
finally:
    backup.rename(artifact)
valid()
print('DOWNLOAD_GUARD_SMOKE_OK: exact bytes/digest, corruption, final symlink, ancestor symlink, missing file, restored download')
