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

The CTest cases `nova_deck_launch_budget_transport_test` and
`nova_deck_native_launch_cancel_test` cover delayed pinned HTTPS replies,
ordinary/resume/lookalike deadlines, cancellation before and after the host
receives a request, UI progress, receipt races, unknown outcomes, and owned
cleanup. They use local fake hosts and do not qualify a physical SteamOS
transition or streaming session.
