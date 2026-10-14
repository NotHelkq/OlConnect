# OlConnect Android Client v1.6.6 Release Notes

### Security & Privacy Fixes
- **Resolve Issue #5 (Split-Tunneling IP Leak)**:
  - Fixed a vulnerability where apps excluded from the VPN or binding via `SO_BINDTODEVICE` to `tun0` were permitted through the tunnel when `connectivity.getConnectionOwnerUid(...)` returned `INVALID_UID`.
  - In v1.6.6, when per-app split tunneling or package routing rules are active, unidentifiable / excluded sockets (`INVALID_UID`) are rejected rather than allowed by default, completely eliminating the VPN server IP leak.
  - Upgraded the packet filter cache key from a 2-port key to a complete 5-tuple hash (`protocol`, `srcIp`, `srcPort`, `dstIp`, `dstPort`), preventing port collision cache poisoning across different destinations.
  - Blocked arbitrary packets targeting port 53 (DNS) from unconditionally bypassing UID-based per-app routing policy when exclusions are set.

### Compatibility
- Fully compatible with `OpenFlux-Android` v1.0.4 and `OlConnect_manager` server-v2.3.6 (including all Russian domestic carriers: Volga, Telemost, Mail.ru, Avito, Cups, Webastream).
