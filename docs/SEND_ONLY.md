# Send-only build (Mnaseb SIM, CARD-25)

A second build of the same app that never reads incoming messages. Why: Google Play Protect names
`RECEIVE_SMS` and `READ_SMS` (not `SEND_SMS`) as what blocks a browser install, and the two-way
build saves every incoming SMS of both SIMs, including a personal one.

## What differs

| | two-way (default `mnaseb-sim-vX.apk`) | send-only (`mnaseb-sim-vX-send-only.apk`) |
|---|---|---|
| application id, signing certificate | `com.mnaseb.sim`, pinned Mnaseb certificate | the same |
| `RECEIVE_SMS`, `READ_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH` | declared | **not declared** |
| SMS / MMS receivers, inbox observers, inbox export | registered and started | **not registered, not started** |
| send, number proof (MO), delivery reports (`SEND_SMS`) | yes | yes |
| STOP word capture and replies | yes | **absent: record opt-outs by hand** |
| number proof by a self-sent SMS (LOOPBACK, needs to receive it) | yes | **not possible**: it can only time out, use the two-way build for it |
| verification code at sign-up | autofilled from the SMS | **typed by hand** (no SMS User Consent API in this app) |

The app says it on the permission screen, in English and Arabic:
"This is the send-only version: it does not read incoming messages and does not capture a STOP word;
record opt-out requests by hand." / "هذا الإصدار للإرسال فقط: لا يقرأ الرسائل الواردة ولا يلتقط كلمة توقف؛ سجّل طلبات الإلغاء يدوياً."

## How it is built

- Build type `sendOnly` (`initWith release`): `./gradlew :app:assembleSendOnly`. The manifest overlay
  `app/src/sendOnly/AndroidManifest.xml` removes the four permissions and the two receivers
  (`tools:node="remove"`). `BuildConfig.SEND_ONLY` is `true` there and gates the code paths
  (`ReceiverService`, `AccountPermissions`, `HomeFragment`, `HomeV2Fragment`, the permission screen).
- Build type `sendOnlyE2e`: the same with the stub-server override, debug-signed, for the emulator job.
- Release: the tag workflow (after the default APK is uploaded, build and guard without secrets, and a failure here never blocks the default release) adds `...-send-only.apk` (and its `.sha256`) as a second asset, after the
  same pinned-certificate gate. The default asset and the release notes' main checksum are unchanged.

## Guards

- `e2e/send-only-guard.sh` (CI job "Build, release guard"): fails if the send-only APK declares any of
  the four permissions or registers an incoming receiver, and requires the two-way release to still
  carry them. The release workflow runs it again before signing.
- Emulator job "Emulator send-only build (API 33)" (`e2e/send-only-e2e.sh`): `dumpsys package` shows none
  of the four permissions, `E2eSendOnlyTest`, sign-up with a typed code, and one test send through the
  stub; the app must not crash.
