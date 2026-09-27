import json
import os
import time
import urllib.error
import urllib.request
import uuid

settings = os.environ
base = settings['YONA_BASE_URL']

def call(path, body=None, control=False, cookie=True):
    headers = {}
    if cookie:
        headers['Cookie'] = settings['YONA_ADMIN_COOKIE']
    if control:
        headers['X-Yona-Queue-Test-Control'] = settings['YONA_QUEUE_TEST_CONTROL_TOKEN']
    if body is not None:
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)

run = call('/__test__/queue/v1/runs', {'scenario': 'large-result', 'runKey': str(uuid.uuid4())}, control=True)
job_id = run['jobIds'][0]
for _ in range(400):
    detail = call('/api/admin/queue/v1/jobs/' + job_id)
    if detail['status'] == 'SUCCEEDED':
        break
    time.sleep(0.05)
else:
    raise AssertionError('Real large-result handler did not complete')
assert detail['result']['sizeBytes'] == '16777216'
assert detail['progress']['counters']['bytes'] == '16777216'

def sample():
    values = []
    for _ in range(40):
        values.append(call('/__test__/queue/v1/pool', control=True, cookie=False)['active'])
        time.sleep(0.025)
    return min(values)

baseline = sample()
request = urllib.request.Request(base + '/api/admin/queue/v1/jobs/' + job_id + '/result',
                                 headers={'Cookie': settings['YONA_ADMIN_COOKIE']})
with urllib.request.urlopen(request, timeout=30) as response:
    assert response.status == 200 and response.headers['Content-Length'] == '16777216'
    response.read(1)
    held = sample()
    print('SLOW_DOWNLOAD_POOL baseline=' + str(baseline) + ' while_client_stalled=' + str(held), flush=True)
    assert held <= baseline, 'A stalled client retained a database connection'
print('SLOW_DOWNLOAD_SMOKE_OK: real 16 MiB artifact, stalled client, no retained database connection')
