import CoreLocation
import MessageUI
import SwiftUI

func validatedPhone(_ input: String) -> String? {
    let number = input.trimmingCharacters(in: .whitespacesAndNewlines).filter { !" .()-".contains($0) }
    return number.range(of: #"^\+?[0-9]{3,15}$"#, options: .regularExpression) == nil ? nil : number
}

struct MessageDraft: Identifiable {
    let id = UUID()
    let recipient: String
    let body: String
}

struct MessageSheet: UIViewControllerRepresentable {
    let draft: MessageDraft
    @Environment(\.dismiss) private var dismiss

    func makeCoordinator() -> Coordinator { Coordinator { dismiss() } }

    func makeUIViewController(context: Context) -> MFMessageComposeViewController {
        let composer = MFMessageComposeViewController()
        composer.recipients = [draft.recipient]
        composer.body = draft.body
        composer.messageComposeDelegate = context.coordinator
        return composer
    }

    func updateUIViewController(_ controller: MFMessageComposeViewController, context: Context) {}

    final class Coordinator: NSObject, MFMessageComposeViewControllerDelegate {
        let close: () -> Void
        init(close: @escaping () -> Void) { self.close = close }

        func messageComposeViewController(
            _ controller: MFMessageComposeViewController, didFinishWith result: MessageComposeResult
        ) {
            close()
        }
    }
}

@MainActor
final class LocationReader: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var pending: CheckedContinuation<CLLocation?, Never>?
    private var timeout: Task<Void, Never>?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    func read() async -> CLLocation? {
        guard pending == nil else { return nil }
        return await withCheckedContinuation { continuation in
            pending = continuation
            switch manager.authorizationStatus {
            case .notDetermined: manager.requestWhenInUseAuthorization()
            case .authorizedAlways, .authorizedWhenInUse: requestFix()
            default: finish(nil)
            }
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard pending != nil else { return }
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse: requestFix()
        case .restricted, .denied: finish(nil)
        default: break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let latest = locations.last
        finish(latest.flatMap { abs($0.timestamp.timeIntervalSinceNow) < 30 ? $0 : nil })
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        finish(nil)
    }

    private func requestFix() {
        guard timeout == nil else { return }
        manager.requestLocation()
        timeout = Task {
            do { try await Task.sleep(for: .seconds(5)) } catch { return }
            finish(nil)
        }
    }

    private func finish(_ location: CLLocation?) {
        manager.stopUpdatingLocation()
        timeout?.cancel()
        timeout = nil
        let continuation = pending
        pending = nil
        continuation?.resume(returning: location)
    }
}
