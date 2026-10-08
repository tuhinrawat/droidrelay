import Foundation
import Network

/// A notification mirrored from the phone.
struct PhoneNotification: Identifiable, Equatable {
    let id: String
    let app: String
    let appName: String
    let title: String
    let text: String
    let time: Date
    let canReply: Bool
}

/// Advertises `_relay._tcp` on the local network and talks protocol v0 (see docs/PROTOCOL.md)
/// with one phone at a time. Spike code: plaintext, no pairing.
final class LinkServer: ObservableObject {
    @Published private(set) var listenerState = "starting"
    @Published private(set) var phoneName: String?
    @Published private(set) var battery: String?
    @Published private(set) var notifications: [PhoneNotification] = []
    @Published private(set) var replyStatus: [String: String] = [:]
    @Published private(set) var log: [String] = []

    private static let maxLineBytes = 64 * 1024
    private let queue = DispatchQueue(label: "app.relay.link")
    private var listener: NWListener?
    private var connection: NWConnection?
    private var buffer = Data()
    private let macName = Host.current().localizedName ?? "Mac"
    private let macId: String = {
        let key = "deviceId"
        if let existing = UserDefaults.standard.string(forKey: key) { return existing }
        let fresh = UUID().uuidString
        UserDefaults.standard.set(fresh, forKey: key)
        return fresh
    }()

    init() {
        start()
    }

    // MARK: - Listening

    private func start() {
        do {
            let listener = try NWListener(using: .tcp)
            listener.service = NWListener.Service(
                name: macName,
                type: "_relay._tcp",
                domain: nil,
                txtRecord: NWTXTRecord(["v": "0"])
            )
            listener.stateUpdateHandler = { [weak self] state in
                self?.record("Listener \(state)")
                self?.onMain { self?.listenerState = "\(state)" }
            }
            listener.newConnectionHandler = { [weak self] newConnection in
                self?.accept(newConnection)
            }
            listener.start(queue: queue)
            self.listener = listener
        } catch {
            listenerState = "failed"
            record("Could not start listener: \(error)")
        }
    }

    private func accept(_ newConnection: NWConnection) {
        if let old = connection {
            record("New phone connection replaces the old one")
            old.cancel()
        }
        connection = newConnection
        buffer.removeAll()
        newConnection.stateUpdateHandler = { [weak self, weak newConnection] state in
            guard let self, let newConnection else { return }
            switch state {
            case .ready:
                self.record("Phone connected from \(newConnection.endpoint)")
                self.send(["type": "hello", "deviceId": self.macId, "name": self.macName,
                           "platform": "mac", "appVersion": "0.1"])
            case .failed(let error):
                self.record("Connection failed: \(error)")
                self.dropConnection(newConnection)
            case .cancelled:
                self.dropConnection(newConnection)
            default:
                break
            }
        }
        newConnection.start(queue: queue)
        receive(on: newConnection)
    }

    private func dropConnection(_ dropped: NWConnection) {
        guard connection === dropped else { return }
        connection = nil
        buffer.removeAll()
        onMain {
            self.phoneName = nil
            self.battery = nil
        }
    }

    private func receive(on conn: NWConnection) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty, self.connection === conn {
                self.buffer.append(data)
                self.drainLines()
            }
            if isComplete || error != nil {
                self.record("Phone disconnected")
                conn.cancel()
                return
            }
            self.receive(on: conn)
        }
    }

    private func drainLines() {
        while let newline = buffer.firstIndex(of: 0x0A) {
            let line = buffer.subdata(in: buffer.startIndex..<newline)
            buffer.removeSubrange(buffer.startIndex...newline)
            handle(line)
        }
        if buffer.count > Self.maxLineBytes {
            record("Dropped an over-long line")
            buffer.removeAll()
        }
    }

    // MARK: - Messages

    private func handle(_ line: Data) {
        guard let object = try? JSONSerialization.jsonObject(with: line) as? [String: Any],
              let type = object["type"] as? String else {
            record("Ignored malformed line")
            return
        }
        switch type {
        case "hello":
            let name = object["name"] as? String ?? "Android phone"
            record("Phone says hello: \(name)")
            onMain { self.phoneName = name }
        case "ping":
            send(["type": "pong"])
        case "pong":
            break
        case "battery":
            if let level = object["level"] as? Int {
                let charging = object["charging"] as? Bool ?? false
                onMain { self.battery = "\(level)%\(charging ? " ⚡" : "")" }
            }
        case "notification.posted":
            guard let id = object["id"] as? String, let app = object["app"] as? String else { return }
            let millis = (object["time"] as? NSNumber)?.doubleValue ?? Date().timeIntervalSince1970 * 1000
            let item = PhoneNotification(
                id: id,
                app: app,
                appName: object["appName"] as? String ?? app,
                title: object["title"] as? String ?? "",
                text: object["text"] as? String ?? "",
                time: Date(timeIntervalSince1970: millis / 1000),
                canReply: object["canReply"] as? Bool ?? false
            )
            onMain {
                self.notifications.removeAll { $0.id == id }
                self.notifications.insert(item, at: 0)
                if self.notifications.count > 50 { self.notifications.removeLast() }
            }
        case "notification.removed":
            guard let id = object["id"] as? String else { return }
            onMain { self.notifications.removeAll { $0.id == id } }
        case "reply.result":
            guard let id = object["id"] as? String else { return }
            let ok = object["ok"] as? Bool ?? false
            let error = object["error"] as? String
            record("Reply \(ok ? "delivered" : "FAILED: \(error ?? "unknown")")")
            onMain { self.replyStatus[id] = ok ? "Reply sent ✓" : "Reply failed: \(error ?? "unknown")" }
        default:
            break // unknown types are ignored on purpose (forward compatibility)
        }
    }

    /// Sends a reply typed on the Mac; the phone delivers it through the app's reply action.
    func reply(to id: String, text: String) {
        onMain { self.replyStatus[id] = "Sending…" }
        queue.async {
            self.send(["type": "notification.reply", "id": id, "text": text])
        }
    }

    /// Must run on `queue`.
    private func send(_ message: [String: Any]) {
        guard let conn = connection else { return }
        var payload = message
        payload["v"] = 0
        guard var data = try? JSONSerialization.data(withJSONObject: payload) else { return }
        data.append(0x0A)
        conn.send(content: data, completion: .contentProcessed { [weak self] error in
            if let error { self?.record("Send failed: \(error)") }
        })
    }

    // MARK: - Helpers

    private func record(_ message: String) {
        let stamp = Self.timeFormatter.string(from: Date())
        onMain {
            self.log.append("\(stamp)  \(message)")
            if self.log.count > 200 { self.log.removeFirst(self.log.count - 200) }
        }
    }

    private func onMain(_ work: @escaping () -> Void) {
        DispatchQueue.main.async(execute: work)
    }

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter
    }()
}
