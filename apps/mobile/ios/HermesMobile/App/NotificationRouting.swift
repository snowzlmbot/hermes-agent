import UIKit
import UserNotifications

public enum NotificationRouteParser {
    public static func route(
        categoryIdentifier: String,
        userInfo: [AnyHashable: Any]
    ) -> NotificationRoute? {
        let keys = Set(userInfo.keys.compactMap { $0 as? String })
        guard userInfo.count == 2,
              NotificationCategory.supportsStoredSessionRouting(categoryIdentifier),
              keys == Set([
                NotificationRouteMetadata.storedSessionIDKey,
                NotificationRouteMetadata.profileScopeKey
              ]),
              let storedSessionID = NotificationRouteMetadata.normalizedStoredSessionID(
                userInfo[NotificationRouteMetadata.storedSessionIDKey] as? String
              ),
              let profileScope = NotificationRouteMetadata.normalizedProfileScope(
                userInfo[NotificationRouteMetadata.profileScopeKey] as? String
              ) else { return nil }
        return NotificationRoute(storedSessionID: storedSessionID, profileScope: profileScope)
    }
}

@MainActor
public final class NotificationResponseRouter {
    public typealias Handler = @MainActor (NotificationRoute) async -> Void

    private var handler: Handler?
    private var pendingRoutes: [NotificationRoute] = []

    public init() {}

    var pendingRouteCount: Int {
        pendingRoutes.count
    }

    public func install(handler: @escaping Handler) async {
        self.handler = handler
        let pending = pendingRoutes
        pendingRoutes.removeAll(keepingCapacity: true)
        for route in pending {
            await handler(route)
        }
    }

    public func receive(route: NotificationRoute) async {
        guard NotificationRouteMetadata.normalizedStoredSessionID(route.storedSessionID) != nil,
              NotificationRouteMetadata.normalizedProfileScope(route.profileScope) != nil else { return }
        guard let handler else {
            pendingRoutes.append(route)
            return
        }
        await handler(route)
    }
}

@MainActor
public final class NotificationAppDelegate: NSObject, UIApplicationDelegate {
    public let responseRouter: NotificationResponseRouter

    public override init() {
        self.responseRouter = NotificationResponseRouter()
        super.init()
    }

    public func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    public func install(appModel: AppModel) async {
        await responseRouter.install { [weak appModel] route in
            await appModel?.handleNotificationRoute(route)
        }
    }

    nonisolated public func handleNotificationResponse(
        categoryIdentifier: String,
        userInfo: [AnyHashable: Any],
        completionHandler: @escaping () -> Void
    ) {
        defer { completionHandler() }
        guard let route = NotificationRouteParser.route(
            categoryIdentifier: categoryIdentifier,
            userInfo: userInfo
        ) else { return }

        Task { @MainActor [weak self] in
            await self?.responseRouter.receive(route: route)
        }
    }
}

extension NotificationAppDelegate: @preconcurrency UNUserNotificationCenterDelegate {
    nonisolated public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([])
    }

    nonisolated public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let content = response.notification.request.content
        handleNotificationResponse(
            categoryIdentifier: content.categoryIdentifier,
            userInfo: content.userInfo,
            completionHandler: completionHandler
        )
    }
}
