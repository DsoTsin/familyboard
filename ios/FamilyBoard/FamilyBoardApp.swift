import CoreLocation
import GRPCCore
import MessageUI
import SwiftUI
import UIKit

@main
struct FamilyBoardApp: App {
    var body: some Scene {
        WindowGroup { BoardScreen() }
    }
}

@MainActor
private struct BoardScreen: View {
    @AppStorage("contactName") private var contactName = ""
    @AppStorage("contactPhone") private var contactPhone = ""
    @AppStorage("server") private var lastServer = ""
    @State private var draftName = ""
    @State private var draftPhone = ""
    @State private var endpoint = ""
    @State private var displayName = ""
    @State private var familyName = ""
    @State private var inviteCode = ""
    @State private var board: Familyboard_V1_Board?
    @State private var activeOrigin: ServerOrigin?
    @State private var invite: String?
    @State private var messageDraft: MessageDraft?
    @State private var notice = ""
    @State private var contactNotice = ""
    @State private var busy = false
    @State private var locating = false
    @State private var confirmAlert = false
    @State private var locationReader = LocationReader()

    private let helpText = "我需要帮忙，请尽快给我打电话。"

    var body: some View {
        NavigationStack {
            Form {
                Section("紧急联系人") {
                    if contactPhone.isEmpty {
                        Text("保存一位家人后，可以快速打电话或发短信。")
                    } else {
                        Text(contactName).font(.headline)
                        Text(contactPhone)
                    }
                    Button("拨打电话") {
                        guard let number = validatedPhone(contactPhone),
                              let url = URL(string: "tel:\(number)") else { return }
                        UIApplication.shared.open(url) { opened in
                            if !opened { contactNotice = "此设备无法打开拨号器。" }
                        }
                    }
                    .tint(.red)
                    .disabled(contactPhone.isEmpty)
                    Button("打开求助短信") { openMessage(contactPhone, body: helpText) }
                        .disabled(contactPhone.isEmpty)
                    Button(locating ? "正在获取位置…" : "附上当前位置并打开短信") {
                        let recipient = contactPhone
                        locating = true
                        Task {
                            let location = await locationReader.read()
                            locating = false
                            let position = location.map {
                                "\n我的位置：https://maps.apple.com/?ll=\($0.coordinate.latitude),\($0.coordinate.longitude)"
                            } ?? "\n暂时无法获取位置。"
                            openMessage(recipient, body: helpText + position)
                        }
                    }
                    .disabled(contactPhone.isEmpty || locating)
                    if !contactNotice.isEmpty { Text(contactNotice).font(.footnote) }
                }
                Section("编辑首选联系人") {
                    TextField("姓名", text: $draftName)
                    TextField("电话号码", text: $draftPhone).keyboardType(.phonePad)
                    Button("保存联系人") {
                        let name = draftName.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !name.isEmpty, let phone = validatedPhone(draftPhone) else {
                            contactNotice = "请输入姓名和有效电话号码。"
                            return
                        }
                        contactName = name
                        contactPhone = phone
                        contactNotice = "联系人已保存到这台设备。"
                    }
                }
                Section("家庭组") {
                    TextField("HTTPS 服务地址", text: $endpoint)
                        .keyboardType(.URL)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .disabled(busy)
                    Button("连接并刷新") { perform { try await refresh($0) } }
                        .disabled(busy)
                    if busy { ProgressView() }
                    if !notice.isEmpty { Text(notice).font(.footnote) }
                    if let board, activeOrigin == ServerOrigin(endpoint), board.hasFamily {
                        familyContents(board)
                    } else {
                        enrollment
                    }
                }
            }
            .navigationTitle("FamilyBoard")
            .task {
                draftName = contactName
                draftPhone = contactPhone
                endpoint = lastServer
                if ServerOrigin(endpoint) != nil {
                    perform(showMissingFamily: false) { try await refresh($0) }
                }
            }
            .sheet(item: $messageDraft) { MessageSheet(draft: $0) }
            .confirmationDialog("发布求助？", isPresented: $confirmAlert, titleVisibility: .visible) {
                Button("发布", role: .destructive) { sendAlert() }
            } message: {
                Text("家庭成员刷新家庭组后可以看到并确认这条求助。")
            }
        }
    }

    private var enrollment: some View {
        Group {
            TextField("你的姓名", text: $displayName)
            TextField("新家庭名称", text: $familyName)
            Button("创建家庭组") {
                var request = Familyboard_V1_CreateFamilyRequest()
                request.displayName = displayName
                request.familyName = familyName
                let payload = request
                perform { origin in
                    let family = try await origin.call {
                        try await $0.createFamily(payload, metadata: $1, options: $2)
                    }
                    board = Familyboard_V1_Board()
                    board?.family = family
                    try await refresh(origin)
                }
            }
            .disabled(busy || displayName.isEmpty || familyName.isEmpty)
            TextField("邀请码", text: $inviteCode)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("用邀请码加入") {
                var request = Familyboard_V1_JoinFamilyRequest()
                request.displayName = displayName
                request.inviteCode = inviteCode
                let payload = request
                perform { origin in
                    let family = try await origin.call {
                        try await $0.joinFamily(payload, metadata: $1, options: $2)
                    }
                    board = Familyboard_V1_Board()
                    board?.family = family
                    inviteCode = ""
                    try await refresh(origin)
                }
            }
            .disabled(busy || displayName.isEmpty || inviteCode.isEmpty)
        }
    }

    private func familyContents(_ current: Familyboard_V1_Board) -> some View {
        Group {
            Text(current.family.name).font(.headline)
            Text(current.family.members.map(\.displayName).joined(separator: "、"))
            Button("生成一次性邀请") {
                perform { origin in
                    let invitation = try await origin.call {
                        try await $0.createInvite(Familyboard_V1_CreateInviteRequest(), metadata: $1, options: $2)
                    }
                    invite = "加入 FamilyBoard\n服务地址：\(origin.text)\n邀请码：\(invitation.code)"
                }
            }
            .disabled(busy)
            if let invite { ShareLink(item: invite) { Text("分享邀请码（24 小时内有效）") } }
            Button("向家庭组发布求助", role: .destructive) { confirmAlert = true }
                .disabled(busy)
            Text("家人打开 App 并刷新后可以查看、确认求助。").font(.footnote)
            if current.alerts.isEmpty { Text("暂无求助记录。") }
            ForEach(current.alerts, id: \.id) { alert in
                VStack(alignment: .leading, spacing: 7) {
                    Text("\(alert.sender.displayName) 请求帮助").font(.headline)
                    Text(Date(timeIntervalSince1970: Double(alert.createdUnixSeconds)).formatted())
                    let acknowledgements = alert.acknowledgements
                    Text(acknowledgements.isEmpty ? "等待家人确认" :
                        "已确认：" + acknowledgements.map { $0.member.displayName }.joined(separator: "、"))
                    let me = current.family.myMemberID
                    if alert.sender.id != me && !acknowledgements.contains(where: { $0.member.id == me }) {
                        Button("确认收到") { acknowledge(alert.id) }.disabled(busy)
                    }
                }
                .padding(.vertical, 4)
            }
        }
    }

    private func openMessage(_ recipient: String, body: String) {
        guard let phone = validatedPhone(recipient) else { return }
        guard MFMessageComposeViewController.canSendText() else {
            UIPasteboard.general.string = body
            contactNotice = "此设备无法打开短信，求助内容已复制。"
            return
        }
        contactNotice = ""
        messageDraft = MessageDraft(recipient: phone, body: body)
    }

    private func perform(showMissingFamily: Bool = true, _ action: @escaping @MainActor (ServerOrigin) async throws -> Void) {
        guard !busy else { return }
        guard let origin = ServerOrigin(endpoint) else {
            notice = "请输入有效的 HTTPS 服务地址。"
            return
        }
        if origin != activeOrigin {
            board = nil
            invite = nil
            activeOrigin = origin
        }
        lastServer = origin.text
        busy = true
        notice = ""
        Task {
            defer { busy = false }
            do {
                try await action(origin)
            } catch let error as RPCError {
                switch error.code {
                case .notFound:
                    if showMissingFamily { notice = "设备尚未加入家庭，或所需的邀请或求助记录已不可用。" }
                case .unavailable, .deadlineExceeded:
                    notice = "服务未能确认请求。你仍可以直接打电话或发短信。"
                default:
                    notice = "家庭服务请求失败，请检查输入后重试。"
                }
            } catch {
                notice = "家庭服务请求失败，请检查地址和设备凭据。"
            }
        }
    }

    private func refresh(_ origin: ServerOrigin) async throws {
        board = try await origin.call {
            try await $0.getBoard(Familyboard_V1_GetBoardRequest(), metadata: $1, options: $2)
        }
    }

    private func sendAlert() {
        perform { origin in
            let key = "pending:\(origin.text)"
            let defaults = UserDefaults.standard
            let id = defaults.string(forKey: key) ?? UUID().uuidString
            defaults.set(id, forKey: key)
            var request = Familyboard_V1_SendAlertRequest()
            request.requestID = id
            let payload = request
            let alert = try await origin.call {
                try await $0.sendAlert(payload, metadata: $1, options: $2)
            }
            defaults.removeObject(forKey: key)
            display(alert)
            notice = "求助已记录，等待家人在 App 内确认。"
        }
    }

    private func acknowledge(_ id: String) {
        perform { origin in
            var request = Familyboard_V1_AcknowledgeAlertRequest()
            request.alertID = id
            let payload = request
            let alert = try await origin.call {
                try await $0.acknowledgeAlert(payload, metadata: $1, options: $2)
            }
            display(alert)
        }
    }

    private func display(_ alert: Familyboard_V1_Alert) {
        guard var current = board else { return }
        let old = current.alerts.filter { $0.id != alert.id }
        current.alerts = Array(([alert] + old).sorted { $0.createdUnixSeconds > $1.createdUnixSeconds }.prefix(50))
        board = current
    }
}
