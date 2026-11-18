# Emulator e2e (Mnaseb SIM fork)

The app on an Android emulator, against a **stub** of the platform.

**Why a stub.** The platform's images are private and a public fork's runners cannot pull them,
so `e2e/stub-server/` (Node, no dependencies) answers the endpoints the app calls. Wire shapes
are copied from the app's JVM tests (`AccountApiTest.kt`, `FakeAppClient.kt`) and models. The stub
is the contract as the app reads it, not the platform: a platform change the stub does not
copy is invisible here. This is stated again in the PR text.

## What runs when (cost guard)

| Event | Runs |
|---|---|
| Draft PR (every push) | `build` (stub self-test, release guard, APKs) + `smoke` (install, sign-up screen opens, stub reachable) |
| PR `ready_for_review` / `synchronize` / `opened`, not draft; push to `mnaseb-sim`; manual | `build` + `full` (API 33) + `direct-boot` (API 30) + `play-protect` (API 34, non-blocking) |
| Tag release | `emulator-e2e-release-install.yml` (optional follow-on, see below) |

Nothing here is a required check; branch protection is not touched. Flakiness never blocks a
merge: a failing run is reported, not re-run to green. The job summary prints wall minutes per job
and a per-job table (`minutes` job).

**Quarantine.** `e2e/quarantine.txt`, one line per class: `test | root cause | ROADMAP row`. The
runner passes the classes as `-e notClass`. A quarantined test stays in the tree and needs a root
cause and a ROADMAP row to return. Tests are tiered with annotations (`E2eSmoke`, `E2eFull`,
`E2eDirectBoot`) selected with `-e annotation`.

## The endpoint override (T2)

Only the `emulatorE2e` build type points the app at `http://10.0.2.2:8787`. It is compile-time:
`emulatorE2e` compiles `app/src/e2eOn/.../E2eOverride.kt`; `debug`, `debugInsecure`, `insecure`
and `release` compile `app/src/e2eOff/.../E2eOverride.kt`, which has no override and no strings.
There is no intent extra, setting or import path. `AccountApi.DEFAULT_BASE_URL` and
`GatewaySettings.PUBLIC_URL` are untouched; `Endpoints` resolves them. `LinkPayload` accepts the
stub's gateway URL only through `E2eOverride.isStubServer`, which is `false` outside `emulatorE2e`.

Guards (fail closed): the JVM test `EndpointOverrideGuardTest` (debug and release variants), and
`e2e/release-guard.sh` in CI, which greps the dex of the built release APK for the marker
`E2E_STUB_OVERRIDE_ACTIVE` and for `10.0.2.2:8787` (must be absent), requires both production URLs
(must be present) and uses the e2e APK as the positive control (marker must be present, so a
broken grep cannot pass). The `emulatorE2e` variant has its JVM unit tests disabled in
`app/build.gradle` because they assert the production URLs.

The e2e tests (`app/src/androidTestE2e`) are only added to the androidTest source set with
`-Pe2eBuildType=emulatorE2e`, so the existing `Instrumented Tests` job (API 25/30/35, debug
build) is untouched.

## What each test proves (in simulation)

| Test | Proves |
|---|---|
| `E2eSmokeTest` | Stub reachable via `10.0.2.2`; welcome screen; create opens permissions or form |
| `E2eSignUpTest` | Permission screen state is real (`AccountPermissions.missing`) and the system dialogs grant it; the national number is never prefilled; sign-up -> code -> enrolment -> linked, with the stub seeing the nonce-suffixed name, a public key and `enrolment/complete`; the linked phone holds an SSE stream and a heartbeat |
| `E2eLineVerifyTest` | Ticking the SIM and tapping verify makes the app call `POST /app/lines/verify` |
| `E2eGatewayTest` | An inbound SMS fired with `adb emu sms send` (by `sms-watcher.sh`, the console is host-side) is reported once on `/inbox`; pause closes the stream and posts `device/pause`, resume reopens it; **one tap on send test message -> one test-message POST -> one `Sent` report** after a settle window |
| `direct-boot.sh` | CARD-21 A1 (FBE), A2 (`ce_available` not true after reboot), A3 (`SSEForegroundService` running, new SSE connection and heartbeat at the stub while locked), A4 (a message pushed over SSE is sent and reported `Sent` while locked), A6 (observed, see below), A8 (jobs present after unlock, no duplicate `Sent`, link intact) |
| `play-protect.sh` | Information only: what the Google Play image does with the sideloaded APK (screenshots, uiautomator dumps, filtered logcat) |

## What an emulator cannot prove (real device only)

- **A5** E2E-encrypted message while locked: the stub has no encryption flow (Keystore use before unlock is only proven on a phone).
- **A6** the carrier-side hold: on a phone Android withholds `SMS_RECEIVED` until unlock (I-1). The emulator may deliver earlier; `direct-boot.sh` records which it did as INFO and only asserts "exactly once after unlock".
- **A7** 15 minutes locked on a real network, Doze, data usage.
- OEM battery and autostart paths (HyperOS, EMUI), SIM and carrier behaviour, real delivery reports (A4 asserts the `Sent` report; `Delivered` needs a network).
- Play Protect's cloud verdict (depends on signature, account, region): the Play image run is observation only. The real answer is CARD-18 item 1 on the customer phone.
- Any platform behaviour the stub does not copy.
- The **release-signed** APK: the keystore is not available to PR jobs, so the PR flow uses the debug-signed `emulatorE2e` build. `emulator-e2e-release-install.yml` (workflow_run after a tag build, or manual with a tag) downloads the published asset with the default token, checks SHA-256 and the signature, installs it and checks the first screen opens; it runs the production build with no stub.

## Run it by hand

```
node e2e/stub-server/selftest.js
node e2e/stub-server/server.js            # 0.0.0.0:8787
./gradlew -Pe2eBuildType=emulatorE2e :app:assembleEmulatorE2e :app:assembleEmulatorE2eAndroidTest
E2E_APK=app/build/outputs/apk/emulatorE2e/app-emulatorE2e.apk \
E2E_TEST_APK=app/build/outputs/apk/androidTest/emulatorE2e/app-emulatorE2e-androidTest.apk \
  bash e2e/run-e2e.sh full            # emulator running, adb on PATH
```

The stub control API (`/__control/*`) injects an outbound message (`POST /inject/outbound`),
asks for an inbound SMS (`POST /inject/inbound-sms`), and reads the app's reports
(`GET /state`, `/inbox`, `/reports`, `/heartbeat`).
