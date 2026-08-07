import Foundation
import UserNotifications

public enum NotificationCategory {
    public static let approval = "HERMES_APPROVAL"
    public static let input = "HERMES_INPUT"
    public static let completion = "HERMES_COMPLETION"
}

public enum NotificationContentFactory {
    public static func completion(sessionTitle: String) -> UNMutableNotificationContent {
        let content = baseContent()
        content.body = String(
            format: String(localized: "notification.completion.body"),
            locale: Locale.current,
            sessionTitle
        )
        content.categoryIdentifier = NotificationCategory.completion
        return content
    }

    public static func approval(sessionID: String) -> UNMutableNotificationContent {
        let content = baseContent()
        content.body = String(localized: "notification.approval.body")
        content.categoryIdentifier = NotificationCategory.approval
        content.userInfo = ["session_id": sessionID]
        return content
    }

    public static func input(sessionID: String) -> UNMutableNotificationContent {
        let content = baseContent()
        content.body = String(localized: "notification.input.body")
        content.categoryIdentifier = NotificationCategory.input
        content.userInfo = ["session_id": sessionID]
        return content
    }

    private static func baseContent() -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = String(localized: "app.name")
        content.sound = .default
        return content
    }
}

public protocol NotificationScheduling: Sendable {
    func requestAuthorization() async -> Bool
    func scheduleCompletion(sessionTitle: String, sessionID: String?) async
    func scheduleApproval(sessionID: String) async
    func scheduleInput(sessionID: String) async
}

public actor LocalNotificationService: NotificationScheduling {
    private let center: UNUserNotificationCenter

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
        return (try? await center.requestAuthorization(options: [.alert, .badge, .sound])) ?? false
    }

    public func scheduleCompletion(sessionTitle: String, sessionID: String?) async {
        let content = NotificationContentFactory.completion(sessionTitle: sessionTitle)
        if let sessionID { content.userInfo = ["session_id": sessionID] }
        await add(content: content, identifier: "completion-\(sessionID ?? UUID().uuidString)")
    }

    public func scheduleApproval(sessionID: String) async {
        await add(
            content: NotificationContentFactory.approval(sessionID: sessionID),
            identifier: "approval-\(sessionID)"
        )
    }

    public func scheduleInput(sessionID: String) async {
        await add(
            content: NotificationContentFactory.input(sessionID: sessionID),
            identifier: "input-\(sessionID)"
        )
    }

    private func add(content: UNNotificationContent, identifier: String) async {
        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: nil)
        try? await center.add(request)
    }
}