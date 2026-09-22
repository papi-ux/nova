"""Render the real standalone library using temporary, loopback-only mTLS hosts."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import ssl
import struct
import subprocess
import tempfile
import threading
import zlib
from urllib.parse import parse_qs, urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", type=Path)
    parser.add_argument("--capture-dir", type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="nova-standard-library-") as temporary:
        root = Path(temporary)
        for name in ("client", "server"):
            subprocess.run([
                "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                "-keyout", str(root / f"{name}.key"), "-out", str(root / f"{name}.crt"),
                "-days", "1", "-subj", f"/CN=Nova {name} fixture",
            ], check=True, capture_output=True, timeout=15)
        client_certificate = (root / "client.crt").read_text()
        client_der = ssl.PEM_cert_to_DER_cert(client_certificate)
        requests = []
        violations = []
        def chunk(kind, data):
            return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind+data))
        # A small protocol fixture, not substitute game artwork.
        poster = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 8, 12, 8, 2, 0, 0, 0))
        poster += chunk(b"IDAT", zlib.compress((b"\0" + b"\x20\x60\x90" * 8) * 12)) + chunk(b"IEND", b"")
        loaded_assets = set()

        class Host(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                url = urlsplit(self.path)
                secure = isinstance(self.connection, ssl.SSLSocket)
                requests.append((secure, url.path))
                if secure and self.connection.getpeercert(binary_form=True) != client_der:
                    violations.append("unexpected client identity")
                status = 200
                body = b""
                if not secure and url.path == "/serverinfo":
                    body = (f'<root status_code="200"><HttpsPort>{tls.server_port}</HttpsPort></root>').encode()
                elif secure and url.path == "/polaris/v1/capabilities":
                    status = 404
                elif secure and url.path == "/appasset":
                    query = parse_qs(url.query)
                    if query.get("AssetType") != ["2"] or query.get("AssetIdx") != ["0"] or not query.get("uniqueid"):
                        violations.append("incorrect pinned app-asset request")
                    app_id = query.get("appid", [""])[0]
                    loaded_assets.add(app_id)
                    if app_id == "42":
                        body = poster
                    else:
                        status = 404
                elif secure and url.path in ("/serverinfo", "/applist"):
                    query = parse_qs(url.query)
                    if not all(query.get(key) for key in ("uniqueid", "uuid", "devicename")):
                        violations.append("missing standard request identifiers")
                    if url.path == "/serverinfo":
                        body = b'<root status_code="200"><uniqueid>fixture-pc</uniqueid><PairStatus>1</PairStatus></root>'
                    else:
                        body = (
                            '<root status_code="200">'
                            '<App><ID>7</ID><AppTitle>Moonlit Harbor</AppTitle><IsHdrSupported>0</IsHdrSupported></App>'
                            '<App><ID>42</ID><AppTitle>Orbit &amp; Beyond</AppTitle><IsHdrSupported>1</IsHdrSupported></App>'
                            '<App><ID>103</ID><AppTitle>&lt;b&gt;Literal title&lt;/b&gt;</AppTitle></App>'
                            '</root>'
                        ).encode()
                else:
                    violations.append(f"unexpected request: {url.path}")
                    status = 405
                self.send_response(status)
                self.send_header("Content-Type", "application/xml")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(root / "server.crt", root / "server.key")
        context.load_verify_locations(root / "client.crt")
        context.verify_mode = ssl.CERT_REQUIRED
        with ThreadingHTTPServer(("127.0.0.1", 0), Host) as http, \
                ThreadingHTTPServer(("127.0.0.1", 0), Host) as tls:
            tls.socket = context.wrap_socket(tls.socket, server_side=True)
            threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in (http, tls)]
            for thread in threads:
                thread.start()
            try:
                identity = {
                    "version": 1, "certificate": client_certificate,
                    "private_key": (root / "client.key").read_text(),
                    "hosts": [{"uuid": "fixture-pc", "name": "Living Room PC", "address": "127.0.0.1",
                               "http_port": http.server_port, "https_port": tls.server_port,
                               "server_certificate": (root / "server.crt").read_text()}],
                }
                identity_path = root / "identity.json"
                identity_path.write_text(json.dumps(identity))
                identity_path.chmod(0o600)
                original_identity = identity_path.read_bytes()
                capture = root / "standard-host-library.png"
                env = dict(os.environ, NOVA_DECK_IDENTITY_DIR=str(root),
                           XDG_CONFIG_HOME=str(root/"config"), QT_QPA_PLATFORM="offscreen", QT_QUICK_BACKEND="software",
                           QT_SCALE_FACTOR="1", QT_SCREEN_SCALE_FACTORS="1", QT_FORCE_STDERR_LOGGING="1",
                           NOVA_DECK_GAMEPAD_DEVICE="/dev/null")
                result = subprocess.run([
                    str(args.binary.resolve()), "--standalone", "--frontend-smoke-capture", str(capture),
                    "--frontend-smoke-exit-after-ms", "1800",
                ], env=env, capture_output=True, text=True, timeout=20)
                assert result.returncode == 0, result.stderr
                assert "status=ok library=gamestream-live games=3 cached=0" in result.stdout, result.stdout
                for title in ("Moonlit Harbor", "Orbit & Beyond", "<b>Literal title</b>"):
                    assert f"{title} [gamestream]" in result.stdout, result.stdout
                assert not any(error in result.stderr for error in (
                    "ReferenceError", "TypeError", "failed to load", "is not a type",
                )), result.stderr
                assert not violations, violations
                assert "42" in loaded_assets, "visible cover was not requested"
                assert "Failed to get image from provider: image://library-art/1/1" not in result.stderr, result.stderr
                assert [r for r in requests if r[1] != "/appasset"] == [(False, "/serverinfo"), (True, "/polaris/v1/capabilities"),
                                    (True, "/serverinfo"), (True, "/applist")], requests
                assert identity_path.read_bytes() == original_identity, "opening the library changed credentials"
                png = capture.read_bytes()
                assert png[:8] == b"\x89PNG\r\n\x1a\n" and struct.unpack(">II", png[16:24]) == (1280, 800)
                if args.capture_dir:
                    args.capture_dir.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(capture, args.capture_dir / capture.name)
            finally:
                for server in (http, tls):
                    server.shutdown()
                for thread in threads:
                    thread.join(timeout=2)
    print("Actual standalone library rendered: pinned standard host, three apps, stable identity, no launch or pairing requests")


if __name__ == "__main__":
    main()
