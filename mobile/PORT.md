# csqtt 2.5.0 — the swg-panel patch, ported (build label 2.5.0, release tag csqtt-2.5.0)

Source: amurcanov's PUBLISHED 2.5.0 — https://github.com/amurcanov/csqtt commit d02da9b, release v2.5.0 (2026-10-20). Its
server build-input hash (his recipe, scripts/server_asset_provenance.ps1) is 61520B20…, the `buildInputSha256` in his v2.5.0
APK's csqtt.server-provenance.json. (The first port, 2026-10-16, was on the pre-release source.zip he sent; rebased here
with no conflicts.)

**Beyond the integration, one datapath fix** (rust-server/tun_device.rs): his small-UDP pin held any UDP flow carrying a
small packet on ONE stream; VK caps an allocation near 2 Mbit/s downlink, so a QUIC-like download fell from 20 to 2.1
Mbit/s. A flow keeps the pin only while it fits in one stream (3/4 of TB_RATE in 250 ms windows; above it bulk for 2 s,
rotating in 16 KiB bursts). Tests downlink_bulk_udp_with_small_packets_is_not_pinned / downlink_game_flow_with_large_packets_stays_pinned.

**2.5.1-2 (2026-10-20):** under `--no-web` the server no longer prints the web panel's generated password (it opens
nothing — no web panel runs — and a secret has no place in the journal), and logs "Web: off (--no-web)" instead of an
address nothing listens on. Nothing else changes.

## What 2.5 changed under the 2.1.9-4 patch

| 2.1.9-4 part | in 2.5 | port |
|---|---|---|
| `--iface` / `--tun-addr` (iface, gw, /24 in dataplane, shard index, route index, address pool) | same consts, plus NEW users: tproxy/proxy-route iptables rules, KernelNetworking firewall rules, MSS purge, `OWNER_TUNNEL_IP` (.2), get_next_ip (.3–.250) | all follow swgpanel |
| `--no-web` (headless) | same shape | same |
| `--no-nat` (inert since 2.1.5) | **2.5 changes the HOST at start**: ip_forward, v6 forwarding, rp_filter all/default=2, default_qdisc=fq, tcp_congestion_control=bbr + 6 tcp sysctls, `modprobe tcp_bbr`, iptables FORWARD ACCEPT in/out, nat MASQUERADE `-s subnet ! -o iface`, ip6tables FORWARD, and deletes mangle TCPMSS rules on the subnet | **meaningful again**: under `--no-nat` none of that runs — the node owns forwarding/NAT/firewall (as for 2.1). Only the per-iface `rp_filter=2` of its own TUN stays (scoped to the interface it creates). |
| `--desired` reconcile loop | — | ported to the device-less entry; a removal/deactivation cuts live sessions as the stock web panel's delete/toggle do (`derived_keys.remove` + `drop_password_sessions`), then `refresh_credentials` |
| `--max-passwords` | 2.5 has NO password cap (the pool .3–.250 is the bound) | accepted, inert (every unit passes it) |
| uplink source check (2.1.9-2) | **native**: `uplink_source_matches` on both write_ingress paths, with a drop counter | IPv4 as stock. Non-IPv4 uplink passes stock's check; under `--desired` it is dropped (parity with 2.1.9-2; our nodes carry no csqtt v6, the TUN has no v6 address). Pre-tunnel `InjectTun` path: stock has no check → ported. |
| password-bound address / device borrow (2.1.9-2) | **native**: no devices at all; the address lives on the password (`passwords.ip`), access is by password alone | dropped |
| Rebind, one config on several devices (2.1.9-3) | **native**: no device lock exists | dropped |
| move-back hold, main password's first bind (2.1.9-4) | nothing moves, no device to bind | dropped |

## Node (swg-noded) consequences
- `_csqtt_read_store` selects `device_id` FROM passwords + reads `devices`: a 2.5 store has neither → the query fails
  silently → the instance reports an empty store. It must read by the line that runs: 2.5 → `passwords.ip`, 2.1 →
  `devices` (a store that visited 2.5 keeps a stale `ip` column under 2.1, and a stale `devices` table under 2.5).
- A switch 2.1 → 2.5 re-addresses users in connect order (2.5 ignores the `devices` table). Back to 2.1: P0 measures.

## P0 (open)
Wire matrix (2.5 brought DTLS, wire revision CSQTT-WIRE-3), store round trip, phone legs.
