# Production bootstrap evidence authentication

R2 is an authenticated isolated bootstrap PASS; R1 remains a failure. This does not approve production release or replace the pending complete1306 code CI.

| Evidence | R1 | R2 |
|---|---|---|
| Actual archive SHA256 | `78f8ce1a48b0815f3e1ba003d9e2b4a4789af21417d68d3be62aa8d2ebf71145` | `1377ccbcfee4a8f5cc15600a1f4439c1c21584da7e8849404eaeea38dd174118` |
| Authenticated regular files |14|12|
| Result / runner exit / cleanup exit |FAIL /1/0|PASS /0/0|
| Positive application starts in raw log |1|2|
| Mail-health failure text occurrences |82|0|

Every regular archive member was checked against its extracted file; duplicate/unsafe paths and non-file/non-directory members were rejected. All captured source hashes match the receipts. R2 Compose/init match the reviewed current deployment candidate. R1→R2 Compose differs only by the optional environment-mail health setting. The exact machine-readable digests in the paired JSON are authoritative.

Both negative logs contain the expected production-secret rejection and one actual367-migration application through178.12. R1 then times out at JSON readiness while mail-health failures repeat; it has no accepted login/role/restart outcome. R2 proves production=true, seed=false, no QA profile, mail-only health disable and database-health not disabled, non-owner warehouse_app flags, no owner membership/schema CREATE, five restricted clock tables, expiry function privileges, zero demo/customer/stock fixtures, platform login and unchanged-image restart. Raw logs independently contain both schema CREATE and policy DELETE permission denials and two completed application starts. Source assertions and the captured safe receipt establish SQL/HTTP/inspect outcomes; separate raw HTTP login/readiness and SQL result payloads were not persisted, so this review does not claim to have reparsed them. Both runs clean their owned containers/network and retain their private database volumes.

The downloaded destination image receipt and transfer manifest match the authenticated1306 source/config IDs and archive hashes. F4 independently rehashed the original CI server/web archives, exact configuration bytes and all9/10 ordered uncompressed layer hashes. Both bootstrap receipts use the destination OCI IDs recorded by the importer. The source-reviewed importer re-exports and compares config/layer bytes; however, destination re-export tar files are not in these downloads. Destination byte identity is therefore adopted from that executed, source-reviewed receipt, not claimed as an independent rehash of remote tar bytes. Preserve both OCI manifest IDs and configuration digests. No server/web image rebuild is claimed.

The bundle also authenticates six amd64 dependency-preparation records and the edge-preparation receipt for a503 setup route with preserved existing configuration. These are preparation receipts; live TLS responses, old edge configuration bytes, network protocol execution and production activation are outside this bundle.

The separate network probe initially expected an absolute OpenAPI URL, but `OpenApiConfig.kt:13` fixes the server URL to `/`. Parent reports network R1 stopped at that fixture assertion before storage/CWMP checks. It must remain failed, with a fresh corrected fixture expecting `/` and reporting gateway/web/login/docs only. That endpoint cannot prove forwarded HTTPS. No network-run outcome is accepted in this bootstrap review.

Raw environment files, login material and application logs remain private. The public record contains only bounded counts, booleans and hashes. No QA, SSH, Docker execution, remote mutation or CI cancellation was performed by F4. Full code CI, network/storage checks, final host configuration and final F4 acceptance remain pending.
