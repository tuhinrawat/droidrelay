import SwiftUI

@main
struct RelaySpikeApp: App {
    @StateObject private var server = LinkServer()

    var body: some Scene {
        MenuBarExtra {
            ContentView()
                .environmentObject(server)
        } label: {
            Image(systemName: server.phoneName == nil ? "circle.dashed" : "dot.radiowaves.left.and.right")
        }
        .menuBarExtraStyle(.window)
    }
}

struct ContentView: View {
    @EnvironmentObject var server: LinkServer

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            header
            Divider()
            if server.notifications.isEmpty {
                Text(server.phoneName == nil
                     ? "Open Relay Spike on your phone and tap “Start keep-alive + connect to Mac”."
                     : "Connected. New phone notifications will appear here.")
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(server.notifications) { item in
                            NotificationRow(item: item)
                        }
                    }
                }
                .frame(maxHeight: 320)
            }
            Divider()
            DisclosureGroup("Log") {
                ScrollView {
                    Text(server.log.suffix(40).reversed().joined(separator: "\n"))
                        .font(.system(size: 10, design: .monospaced))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                }
                .frame(height: 140)
            }
            HStack {
                Text("Listener: \(server.listenerState)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Button("Quit") { NSApplication.shared.terminate(nil) }
            }
        }
        .padding(14)
        .frame(width: 360)
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(server.phoneName ?? "No phone connected")
                    .font(.headline)
                Text(server.phoneName == nil ? "Waiting on Wi-Fi…" : "Connected · Wi-Fi")
                    .font(.caption)
                    .foregroundStyle(server.phoneName == nil ? Color.secondary : Color.green)
            }
            Spacer()
            if let battery = server.battery {
                Text(battery).font(.callout.weight(.semibold))
            }
        }
    }
}

struct NotificationRow: View {
    @EnvironmentObject var server: LinkServer
    let item: PhoneNotification
    @State private var reply = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(item.appName).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                Spacer()
                Text(item.time, style: .time).font(.caption).foregroundStyle(.secondary)
            }
            Text(item.title).font(.subheadline.weight(.semibold))
            Text(item.text).font(.subheadline).fixedSize(horizontal: false, vertical: true)
            if item.canReply {
                HStack {
                    TextField("Reply…", text: $reply)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit(send)
                    Button("Send", action: send)
                        .disabled(reply.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            if let status = server.replyStatus[item.id] {
                Text(status).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color(nsColor: .controlBackgroundColor)))
    }

    private func send() {
        let text = reply.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        server.reply(to: item.id, text: text)
        reply = ""
    }
}
