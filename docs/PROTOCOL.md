# Relay protocol — v0 (Phase 0 spike)

> **Status:** spike only. v0 is **plaintext** and meant for testing on your own
> Wi-Fi. Phase 2 replaces it with v1: QR pairing + encrypted transport.
> Nothing here is final.

## Transport

| Item | v0 choice |
|---|---|
| Discovery | The Mac advertises a Bonjour service `_relay._tcp` with TXT record `v=0`. Android finds it with `NsdManager`. |
| Connection | Android opens a TCP connection to the Mac. |
| Framing | One JSON object per line (UTF-8, ends with `\n`). Max line length 64 KB. |
| Versioning | Every message carries `"v": 0`. Receivers ignore unknown `type`s and unknown fields (forward compatible). |

## Messages

Every message has `v` (int) and `type` (string). Times are Unix milliseconds.

| `type` | Direction | Fields | Meaning |
|---|---|---|---|
| `hello` | both | `deviceId`, `name`, `platform` (`android`/`mac`), `appVersion` | First message after connecting. |
| `ping` / `pong` | both | — | Keep-alive; reply to `ping` with `pong`. |
| `battery` | phone → Mac | `level` (0–100), `charging` (bool) | Battery status. |
| `notification.posted` | phone → Mac | `id`, `app`, `appName`, `title`, `text`, `time`, `canReply` | A notification appeared (or updated) on the phone. `id` is Android's notification key. |
| `notification.removed` | phone → Mac | `id` | Notification dismissed on the phone. |
| `notification.reply` | Mac → phone | `id`, `text` | Reply typed on the Mac, to send through the notification's own reply action. |
| `reply.result` | phone → Mac | `id`, `ok` (bool), `error` (string, optional) | Whether the reply was delivered to the app. |

## Example session

```
→ {"v":0,"type":"hello","deviceId":"a1b2","name":"Pixel 8","platform":"android","appVersion":"0.1"}
← {"v":0,"type":"hello","deviceId":"m9","name":"MacBook Air","platform":"mac","appVersion":"0.1"}
→ {"v":0,"type":"notification.posted","id":"0|com.whatsapp|1|null|10123","app":"com.whatsapp","appName":"WhatsApp","title":"Mom","text":"Coming for dinner?","time":1760000000000,"canReply":true}
← {"v":0,"type":"notification.reply","id":"0|com.whatsapp|1|null|10123","text":"Yes, on my way"}
→ {"v":0,"type":"reply.result","id":"0|com.whatsapp|1|null|10123","ok":true}
```

The same examples live in `protocol/test-vectors/messages.jsonl`. Both the
Kotlin (Android) and Swift (Mac) code must parse every line in that file.

## Planned for v1 (Phase 2)

- QR pairing: the Mac shows a one-time key; both sides show a 6-digit
  confirmation code.
- Encrypted TLS connection using keys pinned at pairing; unpaired devices rejected.
- File transfer, Find my phone, and clipboard messages.
