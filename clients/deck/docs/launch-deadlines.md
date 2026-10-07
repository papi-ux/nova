# Native launch deadlines

The pinned GameStream fetcher gives the exact `/launch` path a 120-second
deadline, matching [Moonlight Qt's launch window](https://github.com/moonlight-stream/moonlight-qt/blob/de2467e433821664cdd2224aad8c89a625be1ad9/app/backend/nvhttp.cpp). This accommodates Polaris's
bounded SteamOS session transition before it sends a launch receipt. `/cancel`
retains its 30-second deadline. Ordinary reads, `/resume`, and paths that only
resemble `/launch` retain the client's ordinary deadline (four seconds in the
production native target).

The native session worker supplies the current launch cancellation predicate
to every GameStream read and the fresh Space authority check. The pinned HTTPS
client checks it before sending and polls it every 20 milliseconds while a
request is pending. The UI can cancel a slow launch without waiting for its
full deadline. This does not change certificate pinning or launch admission.

Aborting a local HTTP request does not prove that the host stopped its pending
transition or failed to launch a game. A missing launch receipt after a
timeout/disconnect is reported as an unconfirmed host outcome. Nova does not
retry a new launch or send a tokenless `/cancel`. If an accepted new-launch
receipt has already arrived, existing cleanup uses its exact session token
through an independent fetcher, even after the launch flag is cancelled.
Failed/cancelled resume preserves the existing game.

## Session-changing GETs are sent once

`/launch`, `/resume` and `/cancel` change session state although they are GETs on
the wire. They do not use `QNetworkAccessManager`. Each request opens a dedicated
`QSslSocket`, and its HTTP/1.1 request is written once, only after:

- the TLS handshake completes;
- the peer certificate is the exact pinned certificate. A leaf that merely chains
  to it is refused, and nothing is sent.

The client certificate (mTLS) is the same as on the ordinary path, the pinned
certificate is the only trust anchor, and only the expected self-signed and
host-name errors are tolerated, and only on that exact certificate.

The request is a bodyless `GET` with the same encoded target and the same `Host`
as the ordinary path; IPv6 literals are bracketed. It sends
`Accept-Encoding: identity` and `Connection: close`.

The socket never:

- reconnects or retries;
- follows a redirect;
- answers an authentication challenge;
- writes the request a second time.

Exact paths only: lookalikes (`/launching`, `/launch/extra`, `/api/launch`),
ordinary reads and all POST/DELETE requests stay on the existing path. The
deadlines above are unchanged. The cancellation predicate is checked before
connecting, again before writing, and every 20 milliseconds while the request is
pending.

The reply is read by a bounded HTTP/1.1 reader:

| Reply framing | What happens |
|---|---|
| `Content-Length` | Read to the exact length. |
| `chunked` (with chunk extensions and trailers) | Read to the final chunk and trailers. |
| Close-delimited | Read, see the limit below. |
| Interim `1xx` | Skipped, up to four. |
| `101` (protocol switch) | Refused. |
| `Transfer-Encoding` other than `chunked`, or both `Transfer-Encoding` and `Content-Length` | Refused. |
| Conflicting or non-numeric lengths, folded headers, a line without CRLF, a malformed status line or chunk | Refused. |
| A `Content-Encoding` other than `identity` | Refused. |

Each reply line is bounded at 8 KiB. Status, header, interim and trailer lines
together are bounded at 32 KiB, and 100 header lines. Chunk-size framing has its
own bound of 64 KiB. The body is bounded by the caller's size limit.

Any of these means the host gave no receipt, so the outcome is unconfirmed:

- a peer close or abort before a complete reply;
- a truncated header, body or chunk;
- an oversized reply;
- anything the reader refuses.

The builder then treats it like any lost reply. A complete `401`/`403`, other
non-2xx status, or `3xx` keeps its status and is not followed.

**Limit.** A close-delimited body ends when the connection ends, but
`QSslSocket` does not say whether that end was an authenticated TLS
`close_notify` or an unframed transport close.

The fixture showed the two arriving the same way:

- a graceful TLS shutdown (`disconnectFromHost`, which sends `close_notify`);
- an abort part-way through the body.

On the first implementation both ended the body as a clean close, and the
abort-truncated `200` was accepted as success. That first failure is retained in
the change's evidence.

A close-delimited success therefore cannot prove it is complete, and a 2xx reply
without `Content-Length` or chunking is treated as no receipt. Polaris sends
`Content-Length`. A close-delimited refusal keeps its status.

### Why not QNetworkAccessManager

Two QNAM-based attempts were rejected.

1. **GET as a `CustomOperation`** (5a8637e, Qt 6.10.0). Qt only checks
   [idempotency](https://github.com/qt/qtbase/blob/5a8637e4516bc48a0b3f4b5ec3b18618b92e7222/src/network/access/qhttpnetworkrequest.cpp#L398-L404) in the
   [unexpected-EOF handler](https://github.com/qt/qtbase/blob/5a8637e4516bc48a0b3f4b5ec3b18618b92e7222/src/network/access/qhttpnetworkconnectionchannel.cpp#L272-L293).
   After the request headers are written the channel is in
   [`WaitingState`](https://github.com/qt/qtbase/blob/5a8637e4516bc48a0b3f4b5ec3b18618b92e7222/src/network/access/qhttpprotocolhandler.cpp#L304). A
   `RemoteHostClosedError` reported while it is waiting or writing
   [resends the current request without that check](https://github.com/qt/qtbase/blob/5a8637e4516bc48a0b3f4b5ec3b18618b92e7222/src/network/access/qhttpnetworkconnectionchannel.cpp#L1031-L1039).
   On local Qt 6.11.2, a server that read `/launch` and closed without replying
   received it twice in every round, for both `get()` and the custom operation.
2. **An empty, non-resettable sequential upload** (`Content-Length: 0`,
   `DoNotBufferUploadData`). It was replayed the same way. Only a consumed body
   byte stopped the resend, and that changes the on-wire GET.

This is not a macOS artifact. With the custom operation in place, the Linux CI
run 1298 standard job also failed the one-delivery assertion
(`https.requests == before + 1`) after a receiptless close. The guarantee
therefore cannot rest on Qt's resend internals.

The CTest cases `nova_deck_launch_budget_transport_test` and
`nova_deck_native_launch_cancel_test` cover delayed pinned HTTPS replies,
ordinary/resume/lookalike deadlines, cancellation before and after the host
receives a request, UI progress, receipt races, unknown outcomes, and owned
cleanup, including lost and partial TLS replies without replay. They use local
fake hosts and do not qualify a physical SteamOS transition or streaming session.

The `--launch-budget` case also runs the session-GET fixture, over a real
mutual-TLS loopback server. It checks:

- that a host which reads `/launch`, `/resume` or `/cancel` and then closes,
  aborts, or closes after a delay receives exactly one bodyless GET, with no
  receipt;
- complete `Content-Length`, chunked and interim receipts;
- refusal, authentication and redirect statuses (never followed);
- lost, partial, oversized and malformed replies;
- cancellation before sending and while pending, and the deadline;
- that a certificate derived from, but not identical to, the pinned one never
  receives a request;
- the bracketed IPv6 `Host`, when IPv6 loopback is available.
