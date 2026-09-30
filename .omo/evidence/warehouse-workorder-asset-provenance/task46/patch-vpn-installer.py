#!/usr/bin/env python3
"""Correct only empty legacy-DNAT handling in a saved 1306 VPN installer.
The original file is retained. No application image or stored server PKI changes.
"""
import difflib,hashlib,json,os,sys
from pathlib import Path
ORIGINAL=r'''  iptables -t nat -S PREROUTING 2>/dev/null \
    | grep -- '-j DNAT' | grep -v -- '--comment ftth-vpn' | grep -F -- "--to-destination $TUN_PREFIX" \
    | while read -r rule; do iptables -t nat ${rule/#-A/-D} 2>/dev/null || true; done'''
CORRECTED=r'''  legacy_dnat_rules=$(iptables -t nat -S PREROUTING)
  printf '%s\n' "$legacy_dnat_rules" \
    | awk -v prefix="$TUN_PREFIX" '/-j DNAT/ && !/--comment ftth-vpn/ && index($0, "--to-destination " prefix)' \
    | while read -r rule; do iptables -t nat ${rule/#-A/-D} 2>/dev/null || true; done'''
def patch(text):
 if text.count(ORIGINAL)!=1 or CORRECTED in text:raise ValueError('Expected exactly one reviewed original legacy cleanup block')
 return text.replace(ORIGINAL,CORRECTED,1)
def main():
 os.umask(0o077)
 original=Path(sys.argv[1]);destination=Path(sys.argv[2]);text=original.read_text();corrected=patch(text)
 with destination.open('x') as f:f.write(corrected)
 destination.chmod(0o700)
 with destination.with_suffix('.patch').open('x') as f:
  f.write(''.join(difflib.unified_diff(text.splitlines(True),corrected.splitlines(True),fromfile='install.original.sh',tofile='install.reviewed.sh')))
 sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
 print(json.dumps({'originalSha256':sha(original),'patchedSha256':sha(destination),'patcherSha256':sha(Path(__file__)),'changedBlock':'legacy DNAT cleanup only; empty matches succeed; listing failure still aborts'}))
if __name__=='__main__':main()
