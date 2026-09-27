F3 workflow review — APPROVE

Reviewed 2026-09-27T09:26:15.755393+00:00. The original independent F3 PASS remains unchanged for application
`1306b65c34167b2d48f4817ce72990fed3b85aa3`. The 2,486 final F3 source inputs still match.
This approval is independent review sign-off; owner acceptance of the overall
warehouse task remains pending.

The reviewer `/root/workflow_final_review` now covers F3 and F4 independently of
the implementing root. F4 was handed over after its original separate reviewer
could not resume and a replacement could not be spawned. Four distinct live
reviewers are not claimed. Existing separate F4 work and all prior F3 evidence
are preserved.

The original execution covers 36 intended browser cases: 24 main cases, two extra
return/reassign/cancel/partial-count cases, four real same-JAR restart cases and
six historical V172-to-178.12 upgrade/restart cases. Eight read-only preflight
probes contain two positives and six expected negatives. Desktop and touch
projects executed; supplemental 768px dark captures were inspected. Restart
readback retained 917500 mm cable, nine available ONUs and one active accepted
ISP-owned loan installation. A real 40000 mm return with a dropped response
replayed the same command and left 60000 mm plus one ONU with the original
technician after reassignment/cancellation. The returned piece was the sole
partial-count entry, preserving 940000 mm and nine available ONUs.

The two subsequent display-only receipt strings were separately reviewed, with
607 web assertions and typecheck/lint/build passing. Prior raw browser captures
were not relabeled and no local browser rerun is claimed for those strings.
Failed and interrupted main/extra attempts remain identified in
[the original verdict](task46/f3-final-verdict.json); the final pass does not erase
them.

Actual public-host R3 smoke adds HTTPS login, platform dashboard, browser reload
and session refresh, mobile drawer open/close, responsive rendering and logout.
The reviewed source uses real responses and TLS verification. Its exit is zero,
error/business-mutation lists are empty, and I personally inspected the 1280px
desktop and 390px mobile screenshots. R1's exact-Email locator timeout remains
a failed private-fixture attempt; R2 and R3 retain separate source/report hashes.
This public smoke does not repeat the warehouse business matrix on production.

No blocking workflow finding remains. Physical GPON/OLT/MikroTik certification,
successful production VPN-peer traffic and native runtime/distribution remain
outside the verified scope. The current CI, actual deployment, external TLS
negative-authentication test and same-image persistence are covered separately
by [F4](f4-release.md). Raw credentials, traces and screenshots remain private.

Safe evidence: [final authentication](task46/f3-f4-final-host-authentication.json)
SHA256 `e09930b0ec09da14345614900dc723c4641b70c188792a2fea61a9bb9ace4a01`; original F3 verdict SHA256
`023be27141824a59c450d2edf1648c1b109b543fab8bf04f241c6a5576a540dd`.
