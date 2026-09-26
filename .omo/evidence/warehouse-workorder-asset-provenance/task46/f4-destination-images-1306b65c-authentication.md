# Destination image content authentication

Both actual destination re-export archives match the authenticated1306 CI application payloads. This resolves only the earlier bootstrap review limitation that destination archive bytes were unavailable; it does not turn pending full CI or network checks into a pass.

| Service | CI configuration digest | Destination OCI manifest digest | Verified ordered layers |
|---|---|---|---|
| server | `sha256:fb928f2d384d0397e15e27b12f5eb2e57a9ba14ba0c88aa25e16247f2316b484` | `sha256:742224b9fa69bd269780f1a7c76c33cfd37a4b033b2032fef553a4c137fb100b` | 9 |
| web | `sha256:0d12c624b41070cb3f585bd1b6d751d08e6e79863a4bdc6c437822f49fb5d903` | `sha256:ffdf1809281b559bbb3f9021fad2cd829ae5f1220e09e0b376bc35da733063e8` | 10 |

F4 independently read and hashed both original CI archives and both destination re-exports. Re-export archive digests match the earlier remote receipt. Configuration bytes are exactly equal, including architecture, runtime configuration and source revision. Every ordered layer was hashed in both archives and matched the configuration's uncompressed rootfs digest. Destination OCI index→manifest→config/layer digests and sizes were also verified; every regular blob is referenced and checked. The recorded destination IDs are actual OCI manifest hashes, while the CI IDs are configuration hashes. The two identifier types correctly differ.

The exact application payload is therefore preserved, including the previously authenticated JAR,367 migrations, application configuration and web assets. No server/web image rebuild, registry publication, Docker execution or SSH was performed by this reviewer.

Deployment commit `db09c3c2b37ad29b07464b05cd02895265f99a0d` contains exactly the eight reviewed deployment/configuration/documentation paths outside evidence files. All eight bytes match the prior candidate hashes; other application/build/test/workflow/migration inputs remain1306. Keep this configuration commit distinct from application commit `1306b65c34167b2d48f4817ce72990fed3b85aa3` in the release manifest.

Full current code CI remains pending. Network R1/R2 outcomes have not been authenticated in this image receipt, and no production activation or final F4 approval is claimed. Exact archive/config/manifest/layer hashes and source bindings are retained in the paired JSON.
