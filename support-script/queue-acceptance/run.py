#!/usr/bin/env python3
"""Run real two-node queue HTTP acceptance with private, disposable H2/storage."""
import json
import os
from pathlib import Path
import secrets
import shlex
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from http.cookies import SimpleCookie

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
JAVA = str(Path(os.environ['JAVA_HOME']) / 'bin' / 'java') if os.environ.get('JAVA_HOME') else 'java'
MAIN = 'com.github.yonaprojects.yona.queue.acceptance.QueueAdminHttpAcceptanceNodeMain'
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def request(base, path, *, cookie=None, token=None, body=None):
    headers = {'Accept': 'application/json'}
    if cookie:
        headers['Cookie'] = cookie
    if token:
        headers['X-Yona-Queue-Test-Control'] = token
    if body is not None:
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        response = HTTP.open(request, timeout=10)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        return response.status, response.headers, response.read()


def csrf_cookie(headers):
    cookies = SimpleCookie()
    for header in headers.get_all('Set-Cookie', []):
        cookies.load(header)
    return cookies['XSRF-TOKEN'].value if 'XSRF-TOKEN' in cookies else None


def session(base, role, token, require_csrf=False):
    status, _, body = request(base, '/__test__/queue/v1/sessions', token=token, body={'role': role})
    if status != 200:
        raise RuntimeError('Session fixture failed for ' + role + ': HTTP ' + str(status))
    cookie = json.loads(body)['cookie']
    status, headers, _ = request(base, '/api/admin/queue/v1/jobs', cookie=cookie)
    csrf = csrf_cookie(headers)
    if not csrf and require_csrf and role in {'member', 'org-admin'}:
        _, headers, _ = request(base, '/users/loginform', cookie=cookie)
        csrf = csrf_cookie(headers)
    if require_csrf and not csrf:
        raise RuntimeError('Application did not issue a CSRF cookie for ' + role)
    return cookie + ('; XSRF-TOKEN=' + csrf if csrf else ''), csrf


def stop(nodes):
    for process, _, _ in nodes:
        if process.poll() is None:
            process.terminate()
    for process, stream, _ in nodes:
        try:
            process.wait(timeout=45)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        stream.close()
    nodes.clear()


def main():
    subprocess.run([str(REPO / 'gradlew'), '--quiet', 'testClasses'], cwd=REPO, check=True)
    output = subprocess.check_output([str(REPO / 'gradlew'), '--quiet', '-I', str(HERE / 'classpath.init.gradle'),
                                      'queueAcceptanceClasspaths'], cwd=REPO, text=True)
    classpaths = dict(line.split('=', 1) for line in output.splitlines() if line.startswith('QUEUE_'))
    if set(classpaths) != {'QUEUE_TEST_CP', 'QUEUE_MAIN_CP'}:
        raise RuntimeError('Missing application runtime classpaths')
    root = Path(tempfile.mkdtemp(prefix='yona-queue-http-'))
    context = os.environ.get('YONA_QUEUE_HTTP_CONTEXT_PATH', '/queue-it')
    token = secrets.token_hex(32)
    environment = {key: value for key, value in os.environ.items()
                   if key in {'HOME', 'PATH', 'TMPDIR', 'LANG', 'LC_CTYPE', 'JAVA_HOME'}}
    environment.update({
        'SPRING_PROFILES_ACTIVE': 'h2',
        'SPRING_THREADS_VIRTUAL_ENABLED': os.environ.get('SPRING_THREADS_VIRTUAL_ENABLED', 'false'),
        'SPRING_DATASOURCE_URL': 'jdbc:h2:file:' + str(root / 'database') + ';AUTO_SERVER=TRUE;NON_KEYWORDS=VALUE',
        'SPRING_DATASOURCE_USERNAME': 'sa', 'SPRING_DATASOURCE_PASSWORD': '',
        'SPRING_DATASOURCE_DRIVER_CLASS_NAME': 'org.h2.Driver',
        'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE': '16', 'SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE': '0',
        'SPRING_JPA_HIBERNATE_DDL_AUTO': 'update', 'SPRING_JPA_SHOW_SQL': 'false',
        'SPRING_JPA_OPEN_IN_VIEW': 'true',
        'SPRING_JPA_PROPERTIES_HIBERNATE_JDBC_TIME_ZONE': 'UTC', 'SPRING_AI_MCP_SERVER_ENABLED': 'false',
        'SERVER_ADDRESS': '127.0.0.1', 'SERVER_SERVLET_CONTEXT_PATH': context,
        'JAVA_TOOL_OPTIONS': '-Dh2.bindAddress=127.0.0.1 -Djdk.tracePinnedThreads=short',
        'SERVER_COMPRESSION_ENABLED': 'true', 'SERVER_COMPRESSION_MIN_RESPONSE_SIZE': '1',
        'YONA_DATA': str(root / 'application-data'), 'YONA_QUEUE_DATA_DIR': str(root / 'queue'),
        'YONA_QUEUE_ACCEPTANCE_ENABLED': 'true', 'YONA_QUEUE_ACCEPTANCE_CONTROL_DIR': str(root / 'control'),
        'YONA_QUEUE_ACCEPTANCE_CONTROL_TOKEN': token, 'YONA_QUEUE_WORKERS': '2',
        'YONA_QUEUE_POLL_MILLIS': '100', 'YONA_QUEUE_LEASE_MILLIS': '4000', 'YONA_QUEUE_HEARTBEAT_MILLIS': '1000',
        'YONA_QUEUE_SHUTDOWN_GRACE_MILLIS': '1000', 'YONA_NOTIFICATION_BYMAIL_ENABLED': 'false',
        'YONA_IMAP_ENABLED': 'false', 'YONA_LDAP_ENABLED': 'false', 'YONA_SSH_RELAY_ENABLED': 'false',
        'YONA_SSH_MINA_ENABLED': 'false', 'YONA_ANALYTICS_SEND_USAGE': 'false',
    })
    reservations = [socket.socket(), socket.socket()]
    try:
        for reservation in reservations:
            reservation.bind(('127.0.0.1', 0))
        ports = [reservation.getsockname()[1] for reservation in reservations]
    finally:
        for reservation in reservations:
            reservation.close()
    bases = ['http://127.0.0.1:' + str(port) + context for port in ports]
    nodes = []
    try:
        for index, (port, base) in enumerate(zip(ports, bases), 1):
            log = root / ('node-' + str(index) + '.log')
            stream = log.open('wb')
            process = subprocess.Popen([JAVA, '-cp', classpaths['QUEUE_TEST_CP'], MAIN,
                                        '--server.port=' + str(port), '--server.address=127.0.0.1'],
                                       cwd=REPO, env=dict(environment, YONA_QUEUE_INSTANCE_ID='queue-http-' + str(index),
                                                        YONA_BASE_URL=base), stdout=stream, stderr=subprocess.STDOUT)
            nodes.append((process, stream, log))
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError('Yona node exited; inspect ' + str(log))
                try:
                    status, _, body = request(base, '/__test__/queue/v1/health', token=token)
                    if status == 200 and json.loads(body) == {'status': 'ready'}:
                        break
                except (OSError, ValueError):
                    pass
                time.sleep(0.1)
            else:
                raise RuntimeError('Yona readiness failed; inspect ' + str(log))
        settings = {'YONA_BASE_URL': bases[0], 'YONA_NODE_2_BASE_URL': bases[1], 'YONA_QUEUE_TEST_CONTROL_TOKEN': token}
        roles = [
            ('admin', 'YONA_ADMIN_COOKIE', 'YONA_CSRF_TOKEN'),
            ('member', 'YONA_MEMBER_COOKIE', 'YONA_MEMBER_CSRF_TOKEN'),
            ('org-admin', 'YONA_ORG_ADMIN_COOKIE', 'YONA_ORG_ADMIN_CSRF_TOKEN'),
            ('pre2fa-admin', 'YONA_PRE2FA_ADMIN_COOKIE', None),
            ('revoke-admin', 'YONA_REVOKE_TEST_ADMIN_COOKIE', None),
            ('disabled-admin', 'YONA_DISABLED_TEST_ADMIN_COOKIE', None),
            ('session-admin', 'YONA_SESSION_TEST_ADMIN_COOKIE', None),
            ('node-two-admin', 'YONA_NODE_2_ADMIN_COOKIE', None),
        ]
        for role, cookie_name, csrf_name in roles:
            cookie, csrf = session(bases[1] if role == 'node-two-admin' else bases[0], role, token, bool(csrf_name))
            settings[cookie_name] = cookie
            if csrf_name:
                settings[csrf_name] = csrf
        env_file = root / 'queue-http.env'
        with os.fdopen(os.open(env_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as stream:
            stream.write(''.join('export ' + key + '=' + shlex.quote(value) + '\n' for key, value in settings.items()))
        print('Queue HTTP fixture ready; private environment: ' + str(env_file), flush=True)
        arguments = sys.argv[1:]
        if arguments == ['--serve']:
            threading.Event().wait()
            return
        test_environment = dict(environment, **settings)
        subprocess.run([sys.executable, str(HERE / 'queue_admin_blackbox.py'), *(arguments or ['-k', 'test_pr03_'])],
                       cwd=REPO, env=test_environment, check=True)
        subprocess.run([sys.executable, str(HERE / 'download_guards.py')], cwd=REPO, env=test_environment, check=True)
        subprocess.run([sys.executable, str(HERE / 'slow_download.py')], cwd=REPO, env=test_environment, check=True)

        stop(nodes)
        production_log = root / 'production-boundary.log'
        with production_log.open('wb') as stream:
            completed = subprocess.run([JAVA, '-cp', classpaths['QUEUE_MAIN_CP'], str(HERE / 'ProductionBoundary.java'),
                                        '--server.port=0', '--server.address=127.0.0.1'],
                                       cwd=REPO, env=environment, stdout=stream, stderr=subprocess.STDOUT, timeout=180)
        if completed.returncode:
            raise RuntimeError('Production boundary failed; inspect ' + str(production_log))
        if 'PRODUCTION_BOUNDARY_SMOKE_OK' not in production_log.read_text():
            raise RuntimeError('Production boundary did not report completion')
        print('QUEUE_HTTP_ACCEPTANCE_OK: REST, artifact guards, slow download and production boundary; logs: ' + str(root), flush=True)
    finally:
        stop(nodes)
        print('Queue fixture processes stopped; private evidence remains at ' + str(root), flush=True)


def terminated(signum, frame):
    raise SystemExit(0)


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, terminated)
    main()
