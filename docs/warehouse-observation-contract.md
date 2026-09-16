# Warehouse Observation Contract

Task23 remains subject to independent verification. This documents implemented
observation behavior, not release approval.

## Clocks And Attribution

- Collector payloads require non-null `collectedAt` and `observedAt` strings.
  Missing/null required fields are malformed HTTP shapes:400, no dedup marker or
  fabricated observation timestamp. A present but unparsable timestamp is retained
  as unassigned evidence, rather than converted to the ingestion clock.
- The72-hour age and5-minute future tolerance are admission windows, not proof
  that future ownership is known. Tolerated future samples become immutable
  `FUTURE_OBSERVATION` evidence; they do not update metrics, live status or alarms.
  They are not automatically finalized later without new evidence.
- Server SNMP polling carries its server-measured acquisition interval separately
  from device timestamp claims. A poll spanning an episode or path transition is
  `POLL_SPANS_TRANSITION`, never relabelled as a sample of the replacement ONU.
- Metric times use microsecond precision. Accepted pipeline metrics retain source,
  receipt time, decision-time episode/assignment revisions, and the topology and
  network edge revisions selected at observation time. Decision-time revisions
  are not represented as the revision that existed at acquisition time.
- Legacy/direct rows without producer evidence remain explicitly unverified;
  their origin is not backfilled. Existing metric bytes are retained and updates
  are prohibited. Timescale chunk retention does not delete episode/path parents.

## Path Evidence

- ONU attachment transitions use the server clock after row serialization.
  A non-advancing clock is rejected rather than repaired by sorting/backdating.
- Network-owned ODP/ODC/PON edge history captures upstream moves independently of
  ONU attachment rows. Customer attribution reads it through a public network API.
  Unknown pre-expansion upstream history is unassigned, not reconstructed from
  today's mutable topology.
- Current GPON profiles explicitly mark raw ONU table indexes
  `UNVERIFIED_INDEX`. They are retained as `UNVERIFIED_PON_IDENTITY`, not treated as
  configured labels such as `1/1/1`. Firmware-specific verified index mapping is
  still required before these hints can support attribution. No decoder is guessed
  from an example integer, and missing port proof never disables path checks.

## Concurrency And Replay

- Observation ingest and physical removal can contend on ONU/asset locks. A
  database concurrency victim returns409 `OBSERVATION_RETRY`; the entire failed
  ingest transaction, including its batch marker, rolls back.
- The collector retries409 with the original batch ID. Authentication/configuration
  failures such as401 remain permanent. Retry does not create a second movement,
  assignment, receipt or metric.
- Batch retention uses the later receipt/retention anchor. Migration conservatively
  extends old markers with uncertain transaction-start clocks. Exact cutoff rows
  remain; only rows strictly older than the cutoff are purged in their tenant.
- Global CPE owner censuses include suspended tenants and use short database
  transactions. No ownership fence is held across ACS network I/O. A device-only
  operation mutex serializes ACS commands, with fresh owner checks after waiting
  and after I/O before returning results or recording success.
- Database-only cache admission retains ownership coordination through commit.
  Cached data cannot become readable solely because it was admitted before a new
  conflicting owner appeared.
- ACS202 is queued, not diagnostic completion. Completion requires post-request
  parameter timestamps and matching request inputs; stale or missing evidence is
  incomplete. Every timestamp must satisfy the bounds, not only the oldest one.
- Legacy compatibility permits missing timestamp evidence, never explicitly
  malformed or out-of-bounds parameter times. Missing siblings cannot conceal a
  known-invalid timestamp; the gateway carries that distinction through reads and
  cache admission.
