# open-banking — code review & fixes (Pollen, step 2 of the mzico Open Banking task)

Source repo: `mzico/agama-lab-projects`, branch `agama-lab-flow-designer`.
Starting commit: `ae8c7dc40d167ae8191bd358ed7f8895d174f55d` (2026-09-17).
Local working branch: `pollen-openbanking-review` (not pushed, per instructions).

Scope of this pass: step 2 (review/repair) and the config-template part of step 6.
Live inspection (step 1) is Fizz's; Consent API TLS trust (step 3) is Honey's —
see the channel thread for the coordination split. Nothing here has touched
the lab server; no build/compile was possible locally (no working JDK — same
architecture-mismatch gap noted in the earlier `agama-knowledge` research
session; `mvn` also not installed here).

## Update 2026-09-22 13:18 UTC — project.json is now the single canonical config doc

Honey independently built `projects/open-banking/deploy/config.template.json` (separate clone,
`agama-lab-projects-honey`, branch `honey-openbanking-docs`) in parallel, before this file's code fixes
landed — it used placeholder key names (`consentApiAuthHeader`, `trustedCallbackSigner`, etc.) that don't
match what the fixed code actually reads via `flowConfig.get(...)`. Caught before Fizz configured the live
deploy from it. Per Fizz's request, folded Honey's still-accurate `tlsTrust` and `testProfile` sections into
`project.json` here (both are documentation-only — not read via `flowConfig`, since TLS trust is a
JVM/container-level concern and the OIDC test client/user are admin-tooling setup, not flow config). **This
`project.json` is now the one canonical config template — don't use the older
`agama-lab-projects-honey/projects/open-banking/deploy/config.template.json` for the real deploy.** Honey's
`test-plan.md` in that same `deploy/` directory is unaffected by this and still the right place for section-7
test results.

## Files changed

- `lib/org/gluu/agama/openbanking/consent/OpenbankingConsentServiceImpl.java` — full rewrite, see below.
- `code/urn.openbanking.psd2.sca.flow` — `userId` now comes from `verifyFinalStatus.userId`, not hardcoded `"admin"`.
- `code/urn.openbanking.psd2.sca.json` — designer metadata kept in sync: same `userId` fix in the
  finish-node `returnVariable` and the assignment node, plus removed the hardcoded demo IP/host from
  `configParams` (now placeholders).
- `project.json` — **new file**, did not exist before. Needed for non-interactive packaging
  (`gama-archieve-cli.py` prompts interactively for `projectName` if `project.json` is absent/incomplete)
  and carries the config template — see "Configuration" below.

## Each flagged issue, and what was done

1. **Undeclared `validationResult` / incomplete return paths.** Confirmed: the file would not compile.
   `validateConsent()` referenced `validationResult` without declaring it in the live code path (the
   declaration was inside the commented-out dead branch), and its catch block fell off the end of a
   non-void method. `verifyJwt()` had the same missing-return-in-catch bug. Fixed: declared
   `validationResult` at the top of the method, added a return in every path including the catch block
   of both methods.

2. **Callback JWT signature verification returning `true` even when cryptographic verification fails.**
   Confirmed at the old `verifyJwtForExternalApp`'s final `else` branch — it logged
   `"Cryptographic provider not able to verify jwt but true"` and then `return true`. Fixed: returns
   `false`. This was the most serious bug in the file — a forged/corrupted callback signature would have
   been accepted.

3. **Initial and final consent checks both accepting `AwaitingAuthorisation`.** Confirmed: both call sites
   used the same `validateConsentStatus()`, which only ever checked `AwaitingAuthorisation`. Fixed: split
   into `isAwaitingAuthorisation()` (initial) and `isFinalAuthorised()` (checks `Authorised`), sharing a
   `fetchConsentStatus()` HTTP helper. The final check is now independent of the App's self-reported
   `consent_status` claim — it always re-queries the Consent Engine.

4. **Outgoing RFAC payload hardcoding `"Authorised"`.** Confirmed in `prepareRfacRespPayload()` — the
   *request* sent to the App pre-declared the answer before the App had done anything. Fixed: removed the
   `consent_status` claim from the outgoing payload entirely; Agama has no status to assert at that point.

5. **Successful flow hardcoding `userId = "admin"`.** Confirmed in the `.flow` file's Finish payload and
   mirrored in the designer JSON. Fixed: the App's callback JWS now must carry a `sub` claim; it's
   extracted, checked for presence, and threaded through as `verifyFinalStatus.userId` into the flow's
   Finish result. **This is a new requirement on the callback contract** — whatever App/IDP signs the
   callback (real or mock) must include `sub` identifying the authenticated user, or the flow now fails
   closed instead of defaulting to `admin`.

6. **Shared singleton/static fields mixing concurrent sessions.** Confirmed: `getInstance()` cached a
   single static `INSTANCE`, and `OPENBANKING_CONSENT_ID`/`CLIENT_ID`/`ACR_VALUE`/`SIGNING_KEY_ID`/
   `SIGN_ALG` were all `public static`. Two concurrent flow executions on the same server would silently
   clobber each other's consent/client/acr state. Fixed: removed the singleton (`getInstance` now returns
   `new OpenbankingConsentServiceImpl(config)` every call, matching the `.flow` file's actual usage — it's
   called once per flow execution and reused only within that execution's own `Call` chain); converted the
   fields to private instance fields; dropped `SIGNING_KEY_ID`/`SIGN_ALG` entirely — they were written but
   never read anywhere in the file.

7. **Request Object processing commented out while `validateConsent()` created a new consent.** Confirmed
   — the real path (verify the incoming Request Object JWT, extract the consent id from its claims) was
   entirely commented out; the live path unconditionally called `createConsent()` (a POST that creates a
   brand-new consent) every time. Fixed: `validateConsent()` now tries the Request Object path first
   (verify JWT → extract `openbanking_intent_id`); `createConsent()` is now a clearly-labeled **TEST-ONLY**
   fallback, gated behind `flowConfig.testCreateConsent == "true"`, off by default. See "Two profiles"
   below.

8. **Hardcoded credentials, tokens, and demo URLs.** Confirmed and removed — literal values are not
   reproduced here (or anywhere in this diff/report), per the "never include secrets in reports" rule; see
   the original pre-fix commit (`ae8c7dc4`) on this branch if the exact old literals ever need auditing:
   - A hardcoded bearer token and a hardcoded `Authorization: Basic` credential in the old
     `validateConsentStatus()` → now `consentApiAuthToken`/`consentApiBasicAuth` from flow config, required
     (throws if missing — fails closed, doesn't silently fall back to the old literals).
   - A hardcoded `x-api-key` and a hardcoded demo JWT bearer token in `createConsent()` → now
     `testConsentApiKey`/`testConsentAccessToken` from config, required only when the TEST-ONLY path runs.
   - `RFAC_APP_URL` default `"https://mmrraju-adapted-crab.gluu.info/rfac-demo.html"` → no default;
     `buildRfacUrl()` now returns `null` (fails closed) if it isn't configured.
   - Designer JSON's `configParams` had the same RFAC demo host plus a real-looking IP
     (`https://74.162.198.218`) as the `consentEngineBaseUrl` default — replaced with placeholders.
   - Left as-is, not a code change: `lib/.../consent/Test.txt` is a pre-existing scratch file in the repo
     with old demo-host JWT examples (`mmrraju-*.gluu.info`). Not referenced by any code path; flagging
     rather than deleting someone else's file outside this review's scope.

## Additional issues found beyond the flagged list

- **Callback binding was entirely missing.** Nothing previously checked that the callback's
  `openbanking_intent_id` matched the consent id *this flow execution* actually validated, or that the
  callback's `client_id` was who Agama expected. Fixed: `verifyExternalAppResult()` now rejects a callback
  whose `openbanking_intent_id` doesn't equal `this.OPENBANKING_CONSENT_ID` (closes the "mismatched
  ConsentId" negative test case from the verification plan), and `verifyJwtForExternalApp()` now requires
  the callback's `client_id` to match a configured `trustedCallbackClientId`, or (if that's not set) the
  `client_id` recorded when this flow's own Request Object was verified. **Without a real Request Object
  path wired up (i.e. in the TEST-ONLY consent-creation profile), `CLIENT_ID` is never set, so
  `trustedCallbackClientId` must be explicitly configured for the mock end-to-end journey to work at all —
  this is intentional fail-closed behavior, not a bug.**
- **No expiry check on the callback.** `verifyJwtForExternalApp()` now rejects a callback with a missing or
  past `exp` claim (closes the "expired callback" negative test case).
- **Replay (`jti`) protection is a known, honest gap.** True cross-session replay defense needs a
  persistent "seen jti" store, which doesn't exist anywhere in this codebase's Agama layer and is out of
  scope for a same-file fix. Not implemented; flagging explicitly rather than faking it. Whoever wires up
  persistence (Redis/LDAP/whatever backs this deployment) should extract `jti` from the callback claims
  (not currently done) and check/record it there.
- **Algorithm confusion.** `verifyJwtForExternalApp`/`verifyJwt` pass whatever `SignatureAlgorithm` is in
  the JWT header straight to `cryptoprovider.verifySignature()` with no explicit allow-list at this layer
  (e.g. rejecting `none`). Not changed — `AbstractCryptoProvider` may already enforce this centrally; opening
  that class was out of scope for this pass. Worth Honey/Fizz confirming while they're in the live JVM.
- **TLS trust is unchanged in this file on purpose.** Both HTTP clients (`fetchConsentStatus`,
  `createConsent`) still use `HttpClient.newBuilder().build()` — i.e. whatever trust store the JVM/container
  is configured with. That's deliberate: this file shouldn't hardcode a custom `SSLContext`/cert material,
  and the "narrowest supported scope" trust configuration Honey is investigating (step 3) should be able to
  land at the JVM/container level without any further code change here, as long as it's scoped to the
  process these `HttpClient` calls run in.

## Two profiles (per the objective's step 5)

The code now supports both, switched purely by `flowConfig`:

- **Real Consent API integration**: leave `testCreateConsent` unset/`false`. `validateConsent()` requires a
  real Request Object JWT in the session and a `trustedCallbackClientId` (or a verified originating
  `client_id`) for the callback.
- **Mock end-to-end integration**: set `testCreateConsent=true` and `trustedCallbackClientId` to the mock
  App/IDP's OIDC client id. `validateConsent()` self-creates a consent via `createConsent()` (now clearly
  logged as `TEST-ONLY`), and the rest of the journey (RFAC redirect, callback verification, final
  Authorised check) runs identically to the real profile.

Never both silently — `createConsent()` cannot run unless `testCreateConsent=true` is explicit.

## Configuration

See `project.json`'s `configs.urn.openbanking.psd2.sca` block for the full placeholder template — every
key the Java code reads from `flowConfig` is documented there. **Do not commit real values into
`project.json` and push it** (this branch is local-only per instructions, but treat the placeholders as
the shape to fill in at deploy time, e.g. via `PUT /jans-config-api/api/v1/agama-deployment/configs/{name}`
post-deploy, which is the source-confirmed mechanism for setting live flow config without baking secrets
into the packaged `.gama` — see `agama-knowledge/deployment-runbook.md` and
`ProjectMetadata.java`/`AgamaDeploymentsResource.getConfigs/setConfigs` in the pinned Janssen source for
where that confirmation comes from).

## Not done in this pass (explicitly out of scope / blocked on others)

- Packaging (`.gama` build) — needs Fizz's live-lab findings on the actual installed Agama version's exact
  packaging expectations before running `gama-archieve-cli.py` for real (the objective is explicit: don't
  guess this).
- TLS trust store wiring for `certificate-chain2.pem` — Honey's lane (step 3).
- Compilation was not attempted — no working local JDK/Maven in this environment (same gap flagged in the
  prior `agama-knowledge` session). First real compile should happen either on the lab box's own toolchain
  or wherever packaging ends up running.
- Replay (`jti`) persistence — see above, needs a real store, not invented here.

## 2026-09-22 — Fizz: missing Finish instructions in failure branches (real lab reproduction)

Live testing on testobrhel9.gluu.info surfaced a real DSL-level bug distinct from the Java fixes above:
`urn.openbanking.psd2.sca.flow`'s both `Otherwise` branches (initial-consent-invalid, and
final-verification-invalid) logged the failure but never called `Finish`. When a flow execution falls
through the loop's last iteration without reaching `Finish`, the Agama engine terminates it with a
generic, un-actionable "No Finish instruction was reached during execution of flow" — not a proper
structured result the Auth Server (or a caller) can branch on, and easy to mistake for an engine bug
rather than the flow's own incompleteness. (An earlier, different-looking crash — "An unexpected error
occurred: index -1, length 0" — turned out to be a JDK 17 Kryo/module-access serialization failure
masking this exact same missing-Finish condition; see `RESEARCH/OPENBANKING_LAB_TESTOBRHEL9_STATE_2026-09-22.md`
for the JVM-level fix that was required before the real, controlled error above could even be observed.)

Fixed by adding `Finish {success:false, error: <message>}` to both `Otherwise` branches, so any failure —
missing/malformed input, failed signature verification, rejected/expired consent, mismatched ConsentId —
now produces a controlled, structured `success:false` result instead of an engine-level crash. This is a
`.flow`-level fix, not a Java change; `OpenbankingConsentServiceImpl.java` was already correctly returning
`valid:false` with a message in every failure case (Pollen's fixes above) — the flow just wasn't acting on
it.

**Designer JSON divergence, deliberately not fixed by hand**: `urn.openbanking.psd2.sca.json` (the visual
Agama Lab designer graph) is not updated to add the two new `Finish` nodes/edges this needs. The runtime
engine only reads the `.flow` text file — the designer JSON is consumed solely by Agama Lab's own visual
editor for re-editing — so this divergence has no runtime effect, but re-opening this project in Agama Lab
will show two failure branches ending without a Finish node in the graph. Regenerating the JSON correctly
requires either using the visual designer directly or writing a small script against its node-graph schema;
hand-editing the graph JSON to splice in two new nodes/edges by hand was judged too easy to get subtly
wrong (dangling edges, orphaned node ids) for a text-only edit pass. Whoever next opens this in Agama Lab
should add the two `Finish` nodes visually and re-export, or accept the two-line manual re-sync documented
here.
