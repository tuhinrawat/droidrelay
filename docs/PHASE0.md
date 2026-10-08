# Phase 0 — Risk checks (runbook)

Goal: find deal-breakers **before** building the product. All code here is
throwaway spike code. Record each result in the table at the bottom.

## What you need
- Your MacBook with Xcode, plus `brew install xcodegen`
- Android Studio (current stable)
- Your Android phone + ideally one **Xiaomi/Redmi or Samsung** (the most aggressive battery killers)
- Phone and Mac on the **same Wi-Fi**

---

## 0.1 · Quick Share receive works in an App-Store-style sandbox

NearDrop (public domain) already ships with the App Sandbox on — its
entitlements file enables only `app-sandbox`, `network.server/client` and
Downloads access. So this test is: **build NearDrop unmodified and use it.**

1. `git clone https://github.com/grishka/NearDrop` and open `NearDrop.xcodeproj` in Xcode.
2. Signing & Capabilities → choose your Apple ID team. Run.
3. On the phone: open a photo → Share → Quick Share → pick your Mac.
4. Repeat with: a 50 MB video, a 1 GB file, 10 photos at once, and from each test phone.

**Pass:** all transfers land in Downloads with the sandbox on.
**Note:** Relay's own Quick Share module comes later (Phase 1); this step only proves the sandbox is not a blocker.

---

## 0.2 · Read notifications and reply (the core feature)

1. Open `android/` in Android Studio → Run on your phone.
2. In **Relay Spike**: tap **“1. Open notification access settings”** → enable *Relay Spike*.
   - If the toggle is greyed out (“Restricted setting”): App info → ⋮ → *Allow restricted settings*. This happens because the spike is sideloaded; Play-installed apps won't hit it.
3. Ask someone to message you on **WhatsApp**, then **SMS**, **Telegram**, **Instagram**, **Slack**.
4. Type a reply in the app and tap **“2. Reply to latest replyable notification”**.
5. Check the reply actually arrived in the other person's chat.

**Pass:** reply works for WhatsApp + SMS on at least 4 of 5 phone brands you can try.
**Record:** which apps show `[can reply]`, which replies arrive, any errors in the log.

---

## 0.3 · Google Play policy pre-check

1. Create the app in Play Console (package `app.relay.spike` or your final ID).
2. Upload the spike to **Internal testing**; fill the declarations honestly
   (notification access is used to mirror and reply on the user's own Mac).

**Pass:** no policy warning/rejection on notification access.
**Also check now:** whether your *personal* developer account must run a closed test (reported: ~12 testers for 14 days) before production.

---

## 0.4 · Staying alive in the background

1. In Relay Spike tap **“Start keep-alive + connect to Mac”** (allow the status notification).
2. Lock the phone and leave it for **24 hours** of normal use. Don't open the app.
3. Open the app → **Share full log file**. Look for:
   - `HEARTBEAT GAP: …s` — the phone froze the app (bad if frequent or long)
   - `SERVICE RESTARTED after being killed` — the phone killed it
4. Repeat after setting Battery → **Unrestricted** in app settings, and compare.
5. **0.4b (Android 13+):** tap **“Associate Mac as companion device”**. Make your Mac discoverable
   (System Settings → Bluetooth open), pick it. The log prints
   `battery exempt BEFORE … / AFTER …`. If AFTER = true, the companion route gives us the
   exemption without asking users to dig through battery settings.

**Pass:** no kills and gaps under 5 minutes over 24 h on at least 4 of 5 brands
(with Unrestricted or companion association).

---

## 0.5 · Mac finds the phone on Wi-Fi + end-to-end message

1. `cd mac && xcodegen && open RelaySpike.xcodeproj` → set your signing team → Run.
2. macOS asks to **find devices on your local network** → Allow. (Note what the prompt looks like — screen 4 of the design depends on it.)
3. On the phone tap **“Start keep-alive + connect to Mac”**.
4. The Mac menu bar panel should show your phone name and battery within seconds.
5. Get a WhatsApp message → it appears on the Mac → type a reply on the Mac → it arrives in WhatsApp.
6. Also test: **deny** the local-network prompt once (then re-enable in System Settings → Privacy & Security → Local Network) and note exactly what breaks.

**Pass:** phone found in under 5 s after Allow; Mac → WhatsApp reply works; time from message
arriving on the phone to showing on the Mac feels instant (target < 3 s).

---

## 0.6 · Business and legal checks (you)

- App Store Connect → Pricing: confirm **₹699** and **₹299** exist for India.
- Lawyer: GPL + App Store; clean-room KDE Connect protocol support.

---

## Results

| # | Device / setup | Result | Notes |
|---|---|---|---|
| 0.1 | | ⬜ | |
| 0.2 | | ⬜ | |
| 0.3 | | ⬜ | |
| 0.4 | | ⬜ | |
| 0.4b | | ⬜ | |
| 0.5 | | ⬜ | |
| 0.6 | | ⬜ | |

**Go / no-go rule:** a red on 0.2 or 0.3 stops or reshapes the product.
Everything else has a fallback.
