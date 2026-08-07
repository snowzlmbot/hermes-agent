import UIKit

@MainActor
public final class BackgroundActivityService {
    private var identifier: UIBackgroundTaskIdentifier = .invalid

    public init() {}

    public func begin() {
        guard identifier == .invalid else { return }
        identifier = UIApplication.shared.beginBackgroundTask(withName: "Hermes response") { [weak self] in
            Task { @MainActor [weak self] in self?.end() }
        }
    }

    public func end() {
        guard identifier != .invalid else { return }
        UIApplication.shared.endBackgroundTask(identifier)
        identifier = .invalid
    }
}