# GPON Profile Evidence

Checked 2026-09-17. Owner decision: physical ZTE/Huawei/FiberHome validation is
deferred, not a task23 gate. **None of these GPON profiles is hardware-validated.**
Offline fixtures prove parser behavior, not device/model/firmware certification.

## Provenance And Scope

- **Vendor-authored definition, public mirror:** Huawei XPON object text reproduced
  by OID Base and the [Huawei-labelled MIB Browser tree][h-tree]. These are not
  official Huawei-hosted downloads. OID Base warns its descriptions may be stale.
- **Different vendor MIB, not interchangeable:** [Observium HUAWEI-GPON-MIB][h-other]
  uses `.2011.5.100`, not this profile's `.2011.6.128`. No units/index semantics
  are transferred between those namespaces.
- **Third-party hints:** ZTE community configurations and the pinned FiberHome
  JavaScript source below are evidence of community usage/contradictions, not
  authoritative GPON MIB definitions.
- No matching official-hosted GPON MIB release or model/firmware applicability was
  established. Previous blanket C300/C320, MA5600/MA5800 and AN5516 support claims
  are withdrawn. Missing documentation is labelled unknown, not a hardware blocker.

## Huawei: Exact XPON Objects

All suffixes below expand from **`1.3.6.1.4.1.2011.6.128.1.1.2`**.

| Suffix | Exact mirrored object | Established semantics | Implementation |
|---|---|---|---|
| `.43.1.3` | [`hwGponDeviceOntSn`][h-sn] | OCTET STRING SIZE(8); **configured** ONT serial; read-write | Read only; eight-octet GPON serial convention, never warehouse/source authority |
| `.46.1.15` | [`hwGponDeviceOntControlRunStatus`][h-status] | `up(1), down(2), invalid(-1)`; invalid means query failed/no information | ONLINE/OFFLINE/UNKNOWN; undocumented `3` is UNKNOWN, not LOS |
| `.51.1.4` | [`hwGponOntOpticalDdmRxPower`][h-rx] | Integer32; optical module receive power, **0.01 dBm** | Raw /100, e.g. -2415 -> -24.15 dBm |
| `.51.1.3` | [`hwGponOntOpticalDdmTxPower`][h-tx] | Integer32; optical module transmit power, **0.01 dBm** | Correct ONT TX source; 245 -> 2.45 dBm |
| `.51.1.6` | [`hwGponOntOpticalDdmOltRxOntPower`][h-olt-rx] | ONT power **received at OLT**; `(raw-10000)/100` dBm | Removed from ONT TX mapping; not substituted into another metric |
| `.46.1.20` | [`hwGponDeviceOntControlRanging`][h-distance] | Integer32, metres; `-1` = query failed/no information | Nonnegative metres, otherwise unavailable |
| `.43.1`, `.46.1`, `.51.1` | [Config][h-config-index], [control][h-control-index], [optical][h-optical-index] entries | All `INDEX {ifIndex, hwGponDeviceOntIndex}` | Join exact returned indexes only; no ifIndex-to-frame/slot/PON decoder |

The MIB establishes serial size, not every textual renderer or vendor code. The
existing four-ASCII-vendor-byte plus four-serial-byte convention is tested with
SNMP4J's printable and hexadecimal rendering. Six-byte MACs, malformed lengths
and non-vendor octets are rejected instead of converted to invented identities.
The generic explicit text-serial mode accepts only the canonical GPON shape.

The exact power pages do not establish sentinel codes. Existing out-of-range
compatibility filters (`2147483647`, `65535`, plausible -50..10 dBm) are not
advertised as vendor-defined sentinels. Missing values remain null. Last-down
cause/times and uptime remain unconfigured; this change invents no formats.

## ZTE: Documentation-Unverified Compatibility Profile

All suffixes expand from **`1.3.6.1.4.1.3902.1012`**. Existing constants remain
compatibility assumptions, **not documented supported behavior**.

| Suffix | Existing role/assumption | Evidence limit |
|---|---|---|
| `.3.28.1.1.5` | Serial; eight-octet decoder | [Community header][z-code] names `zxGponOntDevMgmtProvisionSn`; no matching vendor definition established |
| `.3.28.2.1.4` | Status 1=OFFLINE, 2=LOS, 3=ONLINE | Community name `zxGponOntPhaseState`; enum not vendor-confirmed |
| `.3.50.12.1.1.10` | RX /1000 | [Ubilling C320 configuration][z-config] references RX; units not established |
| `.3.50.12.1.1.14` | TX /1000 | [Third-party C320 notes][z-notes]; not vendor unit proof |
| `.3.11.3.1.6` | Distance interpreted as metres | Exact object name, unit and index unknown |

The previously asserted **0.001 dBm**, status enum, sentinel codes and C300/C320
scope are unverified. Direct OID Base status/RX lookups returned404. Available
ZXANEPON material is EPON and does not validate these GPON objects. No replacement
constants or conversion formulas are guessed. Offline compatibility tests retain
existing valid legacy/no-ODP producer handling without certifying these assumptions.

## FiberHome: Default Profile Unavailable

All suffixes expand from **`1.3.6.1.4.1.5875.800.3`**.

| Previous/candidate suffix | Evidence | Result |
|---|---|---|
| `.9.3.3.1.3` | [`onuPonDesc`][f-desc], OCTET STRING description; third-party code calls it uplink description | Not a GPON serial source; removed |
| `.9.3.3.1.5` | [`onuPonSpeed`][f-speed], Integer32 **Mbit/s** | Not an ONU operational status; removed, never `3 -> LOS` |
| `.9.4.1.1.4`, `.9.4.1.1.3` | Former RX/TX; matching GPON objects/units not established | Unavailable; no /100 or offset claim |
| `.10.1.1.10`, `.10.1.1.11` | [Pinned third-party source][f-code] calls these MAC/status (and aliases MAC as “serials”) | Hints only; MAC must not become a GPON serial |

[Observium GEPON-OLT-COMMON-MIB][f-mib] exposes configuration/whitelist fields,
including `onuSnForLogic` `.1.2.1.4` and `onuMacForPhysical` `.1.1.1.4`; these do
not establish live GPON serial/optical sources. The [supplied LibreNMS path][f-missing]
returned404. No unsafe SET/confirmation workflow is imported.

The default FiberHome profile now has null OIDs/scale and an empty status map.
Polling raises the existing typed `OltProtocolException` with an explicit missing
documentation reason before reading tables; diagnostics show missing essential
roles. This is a scoped unsupported profile, not a silent successful empty poll,
not an inferred substitute mapping, and not a plan blocker. The shared adapter
and safe legacy episode ingestion remain available for valid profiles.

## Path And Compatibility Boundary

Every produced GPON raw index remains `UNVERIFIED_INDEX`, even if its text resembles
`1/1/1`. Unknown connected paths remain unassigned. A unique active legacy/no-ODP
episode can receive valid-time telemetry from a tenant-owned OLT as `EPISODE_ONLY`,
with no verified-path claim and no stock creation. Source, privacy, replay and
temporal gates are unchanged. HSGQ EPON has its separate unchanged adapter/tests;
its field evidence does not certify GPON. Future documentation can refine unknowns
without making physical certification a prerequisite for current task23 closure.

[h-tree]: https://mibbrowser.online/mibdb_search.php?mib=HUAWEI-XPON-MIB
[h-other]: https://mibs.observium.org/mib/HUAWEI-GPON-MIB/tables
[h-sn]: https://oid-base.com/get/1.3.6.1.4.1.2011.6.128.1.1.2.43.1.3
[h-status]: https://oid-base.com/get/1.3.6.1.4.1.2011.6.128.1.1.2.46.1.15
[h-rx]: https://oid-base.com/get/1.3.6.1.4.1.2011.6.128.1.1.2.51.1.4
[h-tx]: https://oid-base.com/get/1.3.6.1.4.1.2011.6.128.1.1.2.51.1.3
[h-olt-rx]: https://oid-base.com/get/1.3.6.1.4.1.2011.6.128.1.1.2.51.1.6
[h-distance]: https://oid-base.com/get/1.3.6.1.4.1.2011.6.128.1.1.2.46.1.20
[h-config-index]: https://oid-base.com/get/1.3.6.1.4.1.2011.6.128.1.1.2.43.1
[h-control-index]: https://oid-base.com/get/1.3.6.1.4.1.2011.6.128.1.1.2.46.1
[h-optical-index]: https://oid-base.com/get/1.3.6.1.4.1.2011.6.128.1.1.2.51.1
[z-code]: https://github.com/chinnurtb/xiaoli/blob/master/node/monitd/include/snmp/zte.hrl
[z-config]: https://github.com/nightflyza/Ubilling/blob/master/config/snmptemplates/ZTE_C320_GPON
[z-notes]: https://noname.com.ua/mediawiki/index.php/ZTE_320
[f-desc]: https://oid-base.com/get/1.3.6.1.4.1.5875.800.3.9.3.3.1.3
[f-speed]: https://oid-base.com/get/1.3.6.1.4.1.5875.800.3.9.3.3.1.5
[f-code]: https://raw.githubusercontent.com/maxthetor/snmp-fiberhome/7f27120b1be5edac02ead433ec4a58c0039553f9/src/oid-fh.js
[f-mib]: https://mibs.observium.org/mib/GEPON-OLT-COMMON-MIB/tables
[f-missing]: https://github.com/librenms/librenms-mibs/blob/031e3c901467b8ccf90b96db2a6ed1dd8df7746f/FIBERHOME-OLT-COMMON-MIB
