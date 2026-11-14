import Foundation
import GRPCCore
import GRPCNIOTransportHTTP2TransportServices
import Security

struct ServerOrigin: Equatable, Sendable {
    let host: String
    let port: Int

    init?(_ value: String) {
        guard let url = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
              url.scheme?.lowercased() == "https",
              let host = url.host, !host.isEmpty,
              url.user == nil, url.password == nil,
              url.path.isEmpty || url.path == "/",
              url.query == nil, url.fragment == nil,
              (1...65535).contains(url.port ?? 443)
        else { return nil }
        self.host = host.lowercased()
        self.port = url.port ?? 443
    }

    var text: String {
        let authority = host.contains(":") && !host.hasPrefix("[") ? "[\(host)]" : host
        return "https://\(authority)\(port == 443 ? "" : ":\(port)")"
    }

    typealias Stub = Familyboard_V1_FamilyBoard.Client<HTTP2ClientTransport.TransportServices>

    func call<T: Sendable>(
        _ action: @Sendable (Stub, Metadata, CallOptions) async throws -> T
    ) async throws -> T {
        let token = try DeviceCredential.get(for: text)
        let metadata: Metadata = ["authorization": "Bearer \(token)"]
        var options = CallOptions.defaults
        options.timeout = .seconds(7)
        let callOptions = options
        return try await withGRPCClient(
            transport: try .http2NIOTS(
                target: .dns(host: host.trimmingCharacters(in: CharacterSet(charactersIn: "[]")), port: port),
                transportSecurity: .tls
            )
        ) { client in
            try await action(Stub(wrapping: client), metadata, callOptions)
        }
    }
}

private enum DeviceCredential {
    struct Failure: Error {}

    static func get(for origin: String) throws -> String {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.familyboard.app.session",
            kSecAttrAccount as String: origin,
        ]
        var result: CFTypeRef?
        var lookup = query
        lookup[kSecReturnData as String] = true
        let status = SecItemCopyMatching(lookup as CFDictionary, &result)
        if status == errSecSuccess,
           let data = result as? Data,
           let token = String(data: data, encoding: .utf8) {
            return token
        }
        guard status == errSecItemNotFound else { throw Failure() }
        var bytes = [UInt8](repeating: 0, count: 32)
        let count = bytes.count
        let random = bytes.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!)
        }
        guard random == errSecSuccess else { throw Failure() }
        let token = bytes.map { String(format: "%02x", $0) }.joined()
        query[kSecValueData as String] = Data(token.utf8)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let added = SecItemAdd(query as CFDictionary, nil)
        if added == errSecDuplicateItem { return try get(for: origin) }
        guard added == errSecSuccess else { throw Failure() }
        return token
    }
}
