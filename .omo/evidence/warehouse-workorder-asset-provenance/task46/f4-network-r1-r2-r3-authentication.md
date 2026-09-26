# Network and storage runtime evidence review

R3 is an authenticated isolated runtime PASS. R1 and R2 remain failed fixture attempts. All35 regular files across the three archives match extracted bytes; captured deployment files are identical to reviewed commitdb09c3c2 and the running application identities remain authenticated1306 images.

| Run | Result | Accepted scope / failure |
|---|---|---|
| R1 | FAIL | Incorrect fixture expected absolute OpenAPI URL; application explicitly configures `/`. Storage and protocol gates were not reached. |
| R2 | FAIL | Gateway web/login/relative OpenAPI and137-byte S3 roundtrip passed; fixture invoked absent `radiusd` executable with docker exec. |
| R3 | PASS | Gateway web/login/relative OpenAPI, direct137-byte signed S3 PUT/GET/DELETE, FreeRADIUS configuration, unauthenticated CWMP401 Digest with zero devices, private NBI200/empty array, source/identity and cleanup assertions passed. |

R1→R2 changed only the private OpenAPI expectation/result label. R2→R3 changed only executable lookup before FreeRADIUS `-XC`. Raw R3 logs independently show one successful FreeRADIUS configuration message, one CWMP401 log entry, one application start and12 stopped containers/13 Compose container/network removals. All runs record scoped Compose cleanup0 and separate private edge-network cleanup0. Environments match successful bootstrap R2 except each owned edge network; ports were reset to unpublished and only gateway plus the dedicated probe joined that network.

R1/R2 retained systemd unit states and journals prove numeric exit1. R3's transient unit was already unloaded: its default `ExecMainStatus=0` is ignored. The journal independently records the R3 PASS message and successful deactivation; numeric runner0 is parent-observed rather than recovered from a retained unit. HTTP/SQL response bodies and generated S3 payload bytes were not saved separately; executed source assertions plus safe receipts support those outcomes. S3 verification uses the configured credentials directly against MinIO, not a new application-upload journey.

The simulated edge uses HTTP and injects forwarded metadata. This proves neither a TLS handshake nor runtime forwarded-scheme handling; the relative OpenAPI URL cannot witness scheme. Actual public-domain/Cloudflare/origin/Drive checks are separate and were not authenticated from these bundles. FreeRADIUS evidence is configuration validation, not live subscriber authentication/accounting or hardware certification.

The separate production-foundation receipt and actual `.exit` file show infrastructure-only startup exit0. Eight infrastructure services are listed; backend, web and gateway remain absent, with CWMP authentication enforced and zero devices. The dedicated runtime receipt records pinned FreeRADIUS image, command `['freeradius','-f']`, running state and restart0, closing the earlier production-debug concern. Database/MinIO/Mongo/NBI ports remain unpublished; intended RADIUS/CWMP/firmware listeners are listed separately in the JSON. The private host image-overlay/wrapper bytes were not included in that receipt bundle.

No QA, SSH, Docker, host mutation or deployment was performed by F4. Current full code CI and final release acceptance remain pending. Exact archive/log/source/image/journal hashes and evidence limits are in the paired JSON.
