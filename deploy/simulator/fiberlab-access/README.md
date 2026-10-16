# Local Fiberlab SNMP access

The mini PC routes `10.203.0.64` through `fiberlab-runtime-1`. Host SNMP requests
work, but requests from `ftth-server-1` cross two Docker bridges and are dropped
by Docker's forwarding policy before reaching Fiberlab.

These files manage a dedicated `FTTH-FIBERLAB` chain in `DOCKER-USER`. It permits
only UDP 161 from the current FTTH server container IP to this one simulator IP,
and established replies. The helper validates that the host route points to the
Fiberlab container and that Fiberlab owns the destination address. It discovers
both bridge interfaces and the source container IP instead of hardcoding them.
No SNMP community, device configuration or credential is stored here.

This is mini PC host administration, separate from FTTH image deployment and the
Tencent network node. Neither the Tencent nor hosted FTTH demo deployment should
install these files.

Install as root on the mini PC after reviewing `fiberlab-snmp-access.json`:

```sh
install -d /usr/local/lib/ftth /etc/ftth
install -m 644 fiberlab-snmp-access.py /usr/local/lib/ftth/
install -m 644 fiberlab-snmp-access.json /etc/ftth/
install -m 644 ftth-fiberlab-access.service ftth-fiberlab-access.timer /etc/systemd/system/
python3 /usr/local/lib/ftth/fiberlab-snmp-access.py --config /etc/ftth/fiberlab-snmp-access.json
systemd-analyze verify /etc/systemd/system/ftth-fiberlab-access.service /etc/systemd/system/ftth-fiberlab-access.timer
systemctl daemon-reload
systemctl start ftth-fiberlab-access.service
systemctl enable --now ftth-fiberlab-access.timer
```

The first Python invocation only displays the planned rules. The service applies
them atomically to its own chain. The timer reconciles every 30 seconds, restoring
access after Docker restarts and updating the source IP after FTTH is redeployed.
Existing routes, other firewall rules and other applications' containers remain
owned by their respective services.

Check `systemctl status ftth-fiberlab-access.timer` and
`journalctl -u ftth-fiberlab-access.service`. A missing container or mismatched
route fails validation instead of granting broader network access.

Rollback as root:

```sh
systemctl disable --now ftth-fiberlab-access.timer
systemctl stop ftth-fiberlab-access.service
iptables -w 5 -D DOCKER-USER -j FTTH-FIBERLAB
iptables -w 5 -F FTTH-FIBERLAB
iptables -w 5 -X FTTH-FIBERLAB
```

Validation and activation evidence is recorded in the pull request. The private
operator recovery directory is `.omo/runtime/fiberlab-access/`; host backups are
stored under `/opt/ftth/backups/fiberlab-access-<timestamp>`.
