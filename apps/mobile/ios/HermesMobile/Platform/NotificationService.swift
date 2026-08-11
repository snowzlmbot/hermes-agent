import CryptoKit
import Foundation
import UserNotifications

public enum NotificationCategory {
    public static let approval = "HERMES_APPROVAL"
    public static let input = "HERMES_INPUT"
    public static let completion = "HERMES_COMPLETION"

    public static func supportsStoredSessionRouting(_ identifier: String) -> Bool {
        identifier == approval || identifier == input || identifier == completion
    }
}

public struct NotificationRoute: Equatable, Sendable {
    public let storedSessionID: String
    public let profileScope: String

    public init(storedSessionID: String, profileScope: String) {
        self.storedSessionID = storedSessionID
        self.profileScope = profileScope
    }
}

public enum NotificationRouteMetadata {
    public static let storedSessionIDKey = "stored_session_id"
    public static let profileScopeKey = "profile_scope"
    private static let maximumStoredSessionIDByteCount = 1_024

    public static func normalizedStoredSessionID(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard value == trimmed,
              !trimmed.isEmpty,
              trimmed.utf8.count <= maximumStoredSessionIDByteCount else { return nil }
        return trimmed
    }

    public static func profileScope(for sessionSelectionScope: String) -> String {
        SHA256.hash(data: Data(sessionSelectionScope.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }

    public static func normalizedProfileScope(_ value: String?) -> String? {
        guard let value,
              value.count == 64,
              value.allSatisfy({ $0.isHexDigit && !$0.isUppercase }) else { return nil }
        return value
    }

    public static func userInfo(route: NotificationRoute) -> [AnyHashable: Any] {
        guard let storedSessionID = normalizedStoredSessionID(route.storedSessionID),
              let profileScope = normalizedProfileScope(route.profileScope) else { return [:] }
        return [
            storedSessionIDKey: storedSessionID,
            profileScopeKey: profileScope
        ]
    }
}

enum NotificationDeliveryPolicy {
    static func shouldSchedule(
        isSceneActive: Bool,
        isAuthorized: Bool,
        route: NotificationRoute?
    ) -> Bool {
        guard let route else { return false }
        return !isSceneActive
            && isAuthorized
            && NotificationRouteMetadata.normalizedStoredSessionID(route.storedSessionID) != nil
            && NotificationRouteMetadata.normalizedProfileScope(route.profileScope) != nil
    }
}

public enum NotificationContentFactory {
    public static func completion(
        sessionTitle _: String,
        route: NotificationRoute
    ) -> UNMutableNotificationContent {
        let content = baseContent(route: route)
        content.body = String(localized: "notification.completion.body")
        content.categoryIdentifier = NotificationCategory.completion
        return content
    }

    public static func approval(route: NotificationRoute) -> UNMutableNotificationContent {
        let content = baseContent(route: route)
        content.body = String(localized: "notification.approval.body")
        content.categoryIdentifier = NotificationCategory.approval
        return content
    }

    public static func input(route: NotificationRoute) -> UNMutableNotificationContent {
        let content = baseContent(route: route)
        content.body = String(localized: "notification.input.body")
        content.categoryIdentifier = NotificationCategory.input
        return content
    }

    private static func baseContent(route: NotificationRoute) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = String(localized: "app.name")
        content.sound = .default
        content.userInfo = NotificationRouteMetadata.userInfo(route: route)
        return content
    }
}

public protocol NotificationScheduling: Sendable {
    func requestAuthorization() async -> Bool
    func scheduleCompletion(sessionTitle: String, route: NotificationRoute) async
    func scheduleApproval(route: NotificationRoute) async
    func scheduleInput(route: NotificationRoute) async
}

public actor LocalNotificationService: NotificationScheduling {
    private let center: UNUserNotificationCenter
    private var authorizationGranted = false

    public init(center: UNUserNotificationCenter = .current()) {
        self.center = center
    }

    public func requestAuthorization() async -> Bool {
        let approval = UNNotificationCategory(
            identifier: NotificationCategory.approval,
            actions: [],
            intentIdentifiers: [],
            options: []
        )
        let input = UNNotificationCategory(
            identifier: NotificationCategory.input,
            actions: [],
            intentIdentifiers: [],
            options: []
        )
        let completion = UNNotificationCategory(
            identifier: NotificationCategory.completion,
            actions: [],
            intentIdentifiers: [],
            options: []
        )
        center.setNotificationCategories([approval, input, completion])

        do {
            authorizationGranted = try await center.requestAuthorization(options: [.alert, .badge, .sound])
        } catch {
            authorizationGranted = false
        }
        return authorizationGranted
    }

    public func scheduleCompletion(sessionTitle: String, route: NotificationRoute) async {
        guard NotificationRouteMetadata.userInfo(route: route).count == 2 else { return }
        await add(
            content: NotificationContentFactory.completion(
                sessionTitle: sessionTitle,
                route: route
            ),
            identifier: "completion-\(UUID().uuidString)"
        )
    }

    public func scheduleApproval(route: NotificationRoute) async {
        guard NotificationRouteMetadata.userInfo(route: route).count == 2 else { return }
        await add(
            content: NotificationContentFactory.approval(route: route),
            identifier: "approval-\(UUID().uuidString)"
        )
    }

    public func scheduleInput(route: NotificationRoute) async {
        guard NotificationRouteMetadata.userInfo(route: route).count == 2 else { return }
        await add(
            content: NotificationContentFactory.input(route: route),
            identifier: "input-\(UUID().uuidString)"
        )
    }

    private func add(content: UNNotificationContent, identifier: String) async {
        guard authorizationGranted else { return }
        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: nil)
        try? await center.add(request)
    }
}
