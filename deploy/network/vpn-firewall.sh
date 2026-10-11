#!/usr/bin/env bash
# Restore the hub's own forwarding rules when OpenVPN starts after a reboot.
set -euo pipefail
subnet=10.208.67.0/24
iptables -w 5 -t nat -C POSTROUTING -d "$subnet" -j MASQUERADE 2>/dev/null ||
  iptables -w 5 -t nat -A POSTROUTING -d "$subnet" -j MASQUERADE
iptables -w 5 -C FORWARD -d "$subnet" -j ACCEPT 2>/dev/null ||
  iptables -w 5 -I FORWARD 1 -d "$subnet" -j ACCEPT
iptables -w 5 -C FORWARD -s "$subnet" -j ACCEPT 2>/dev/null ||
  iptables -w 5 -I FORWARD 1 -s "$subnet" -j ACCEPT
