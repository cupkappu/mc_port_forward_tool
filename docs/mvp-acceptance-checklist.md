# MVP Acceptance Checklist

This checklist converts PRD Section 13 into pass/fail evidence rows. Each row
captures the criterion, the evidence to gather, and the action used to
produce it.

## Setup

1. Build the matrix: `scripts/test_matrix.sh`.
2. Stand up a real Minecraft `1.20.1` or `1.21.1` client and matching Fabric server.
3. Install the version-matching mod jar into both.
4. Configure both sides with matching `psk` and a fixed server target
   `127.0.0.1:10000`.
5. Run `scripts/e2e/echo_server.py --host 127.0.0.1 --port 10000` on the server host.
6. Run a real client with a whitelisted player UUID and connect.

## Pass/Fail Rows

| # | Criterion (PRD §13) | Evidence | Command / Action |
|---|----------------------|----------|-------------------|
| 1 | Player starts real Minecraft client with client mod installed | Client log contains `local listener bound to 127.0.0.1:25580` | `tail -f logs/latest.log` |
| 2 | Player connects to Fabric server with server mod installed | Server log contains `player <uuid> joined; tunnel session ready` | `tail -f logs/latest.log` on server |
| 3 | Client mod listens on `127.0.0.1:25580` | Log line `local listener bound to 127.0.0.1:25580`; `lsof -iTCP:25580` shows the process | `lsof -iTCP:25580 -sTCP:LISTEN` |
| 4 | Server mod dials `127.0.0.1:10000` | Server log on OPEN contains a successful TCP dial; `tcpdump` shows the SYN to port 10000 | `tcpdump -i lo 'tcp port 10000'` |
| 5 | External tool can connect to the client local port and build a TCP byte stream | `tcp_probe.py` connects without immediate close | `scripts/e2e/tcp_probe.py --host 127.0.0.1 --port 25580` |
| 6 | Bytes flow through Minecraft client connection | Deterministic probe bytes echo exactly after Minecraft round-trip | `scripts/e2e/tcp_probe.py --bytes 4096` |
| 7 | Server mod forwards bytes to local external tool | Server-side `echo_server.py` receives and echoes what the client probe sent | As above |
| 8 | Multiple concurrent TCP connections work | Four simultaneous probes all echo exact payloads | `scripts/e2e/tcp_probe.py --connections 4 --bytes 16384` |
| 9 | Minecraft client does not freeze | Open the F3 debug overlay; ms/tick stays in single digits during active streams | Observe F3 debug overlay |
| 10 | Minecraft server TPS does not drop noticeably | `/mspt` and `/tps` stay near 20 during active streams | `/mspt` and `/tps` commands |
| 11 | Player disconnect clears all streams | Server log shows `player disconnected; tunnel session torn down`; registry size drops to 0 | Disconnect the client and inspect server log |
| 12 | Unauthorized players cannot use the transport layer | Edit server `allowed_players` to remove the test player; reconnect; AUTH fails and the server log shows no tunnel session created | Modify config, restart, reconnect |

## Evidence Collection

For automated evidence, run:

```
scripts/test_matrix.sh
```

Unit tests (always run):

- `dev.kifuko.mctransport.config.*Test`
- `dev.kifuko.mctransport.protocol.*Test`
- `dev.kifuko.mctransport.crypto.*Test`
- `dev.kifuko.mctransport.stream.*Test`
- `dev.kifuko.mctransport.buffer.*Test`
- `dev.kifuko.mctransport.auth.*Test`
- `dev.kifuko.mctransport.net.*Test`
- `dev.kifuko.mctransport.client.ClientTunnelSessionTest`
- `dev.kifuko.mctransport.server.PlayerTunnelSessionAuthTest`

## Pass/Fail Definition

- **Pass**: All 12 rows above have matching log evidence.
- **Fail**: Any row missing or mismatching.
