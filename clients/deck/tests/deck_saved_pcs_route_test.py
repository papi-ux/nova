"""Exercise real Nova navigation with an isolated identity and loopback-only fixtures."""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time

binary = str(Path(sys.argv[1]).resolve())

def command(*args):
    return subprocess.run(args, check=True, text=True, capture_output=True).stdout.strip()

with tempfile.TemporaryDirectory(prefix='nova-management-cycle-') as folder:
    profile = Path(folder)
    command('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(profile/'key.pem'),
            '-out', str(profile/'cert.pem'), '-days', '1', '-subj', '/CN=Nova management fixture')
    certificate = (profile/'cert.pem').read_text()
    key = (profile/'key.pem').read_text()
    http = socket.socket()
    http.bind(('127.0.0.1', 0))
    http.listen()
    tls = socket.socket()
    tls.bind(('127.0.0.1', 0))
    tls.listen()
    def serve(listener, is_http):
        while True:
            try:
                conn, _ = listener.accept()
                with conn:
                    conn.settimeout(1)
                    conn.recv(8192)
                    if is_http:
                        body = f'<root status_code="200"><HttpsPort>{tls.getsockname()[1]}</HttpsPort></root>'.encode()
                        conn.sendall(b'HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: '+str(len(body)).encode()+b'\r\n\r\n'+body)
            except OSError:
                return
    for listener, mode in ((http, True), (tls, False)):
        threading.Thread(target=serve, args=(listener, mode), daemon=True).start()
    identity = {'version': 1, 'certificate': certificate, 'private_key': key,
                'hosts': [{'uuid':'fixture-pc', 'name':'Local fixture PC', 'address':'127.0.0.1',
                           'http_port':http.getsockname()[1], 'https_port':tls.getsockname()[1],
                           'server_certificate':certificate}]}
    path = profile/'identity.json'
    path.write_text(json.dumps(identity))
    path.chmod(0o600)
    env = dict(os.environ, NOVA_DECK_IDENTITY_DIR=str(profile), QT_QUICK_BACKEND='software',
               XDG_CONFIG_HOME=str(profile/'config'), QT_QPA_PLATFORM='xcb', QT_SCALE_FACTOR='1', QT_SCREEN_SCALE_FACTORS='1',
               QT_FORCE_STDERR_LOGGING='1', NOVA_DECK_GAMEPAD_DEVICE='/dev/null')
    log = profile/'app.log'
    with log.open('w') as output:
        app = subprocess.Popen([binary, '--standalone'], env=env, stdout=output, stderr=output)
        def window(title):
            deadline = time.monotonic()+12
            while time.monotonic()<deadline:
                if app.poll() is not None:
                    raise AssertionError(f'Nova exited {app.returncode}: {log.read_text()}')
                query = subprocess.run(['xdotool','search','--onlyvisible','--pid',str(app.pid),'--name',title],
                                       capture_output=True,text=True)
                if query.returncode == 0 and query.stdout.strip():
                    return query.stdout.strip().splitlines()[-1]
                time.sleep(.05)
            raise AssertionError(f'Window {title!r} not found: {log.read_text()}')
        try:
            for _ in range(2):
                library = window('^Nova Deck$')
                command('xdotool','windowfocus','--sync',library)
                command('xdotool','key','Left','Left','Return','Down','Return')
                manager = window('Saved PCs')
                command('xdotool','windowfocus','--sync',manager)
                command('xdotool','key','Escape')
                window('^Nova Deck$')
                assert json.loads(path.read_text()) == identity, 'navigation changed saved identity'
            library = window('^Nova Deck$')
            command('xdotool','windowfocus','--sync',library)
            command('xdotool','key','Left','Left','Return','Down','Return')
            manager = window('Saved PCs')
            command('xdotool','windowfocus','--sync',manager)
            command('xdotool','key','Return')
            time.sleep(.15)
            command('xdotool','key','Left','Return')
            deadline=time.monotonic()+5
            while json.loads(path.read_text())['hosts'] and time.monotonic()<deadline:
                time.sleep(.05)
            assert not json.loads(path.read_text())['hosts'], 'confirmed local forget did not persist'
            assert json.loads(path.read_text())['certificate']==certificate, 'local forget replaced identity'
            # Empty list focuses Add PC; right moves to Close.
            time.sleep(.15)
            command('xdotool','key','Right','Return')
            app.wait(timeout=5)
            assert app.returncode==0, f'close returned {app.returncode}'
            errors=log.read_text()
            assert not any(x in errors for x in ('ReferenceError','TypeError','failed to load','is not a type')), errors
            print('Actual Nova window cycle passed: library -> saved PCs -> rebuilt library twice, local forget, stable identity, clean exit')
        finally:
            if app.poll() is None:
                app.terminate()
                app.wait(timeout=5)
    http.close()
    tls.close()
