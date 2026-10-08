# DroidRelay

Connect an Android phone to a Mac: notifications and replies, files and photos,
clipboard, Find my phone — plus Quick Share receiving with nothing to install
on the phone. Mac App Store app (7-day free trial, then ₹699 one-time; ₹299 per
extra phone) + Android companion app.

> **Current phase: 0 — risk checks.** Everything in this repo is spike code
> built to answer the questions in [docs/PHASE0.md](docs/PHASE0.md).

## Repo layout

| Folder | What | Build |
|---|---|---|
| `protocol/` | Message format shared by both apps, with unit tests and shared test vectors | `cd protocol && gradle test` (any JDK 17+) |
| `android/` | Android spike app: notification capture + reply, keep-alive, Mac link | Open in Android Studio → Run |
| `mac/` | Mac spike app: menu bar, Wi-Fi discovery, notification mirror + reply | `brew install xcodegen`, then `cd mac && xcodegen && open RelaySpike.xcodeproj` |
| `docs/` | [Protocol](docs/PROTOCOL.md), [Phase 0 runbook](docs/PHASE0.md), [Roadmap](docs/ROADMAP.md) | — |

## Phase 0 quick start

1. **Mac:** generate and run `mac/` (above). Allow the *local network* prompt.
2. **Phone:** run `android/` from Android Studio. In *Relay Spike*:
   enable notification access, then **Start keep-alive + connect to Mac**.
3. Get a WhatsApp message. It shows up in the Mac menu bar; reply from the Mac.
4. Follow [docs/PHASE0.md](docs/PHASE0.md) and fill in the results table.

## Status

| Part | State |
|---|---|
| Protocol v0 codec | ✅ 7 unit tests passing |
| Android spike | ⚠️ Written, **not yet compiled** — first build happens in your Android Studio |
| Mac spike | ⚠️ Written, **not yet compiled** — first build happens in your Xcode |
