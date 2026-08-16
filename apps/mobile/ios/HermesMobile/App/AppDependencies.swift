import Foundation

public struct AppDependencies: Sendable {
    public let profileRepository: GatewayProfileRepository
    public let notificationService: any NotificationScheduling
    public let attachmentImporter: any AttachmentImporting
    public let socketFactory: HermesGatewayTransport.SocketFactory
    public let urlSession: URLSession
    public let allowsInsecureTransport: Bool

    public init(
        profileRepository: GatewayProfileRepository,
        notificationService: any NotificationScheduling,
        attachmentImporter: any AttachmentImporting,
        socketFactory: @escaping HermesGatewayTransport.SocketFactory = { URLSessionGatewaySocket(url: $0) },
        urlSession: URLSession = .shared,
        allowsInsecureTransport: Bool = false
    ) {
        self.profileRepository = profileRepository
        self.notificationService = notificationService
        self.attachmentImporter = attachmentImporter
        self.socketFactory = socketFactory
        self.urlSession = urlSession
        self.allowsInsecureTransport = allowsInsecureTransport
    }

    public static var live: AppDependencies {
        let allowsInsecureTransport = Bundle.main.object(
            forInfoDictionaryKey: "HermesAllowsInsecureTransport"
        ) as? Bool == true
        return AppDependencies(
            profileRepository: GatewayProfileRepository(
                profileStore: UserDefaultsGatewayProfileStore(),
                credentialStore: KeychainCredentialStore(),
                sessionSelectionStore: UserDefaultsStoredSessionSelectionStore(),
                allowsInsecureTransport: allowsInsecureTransport
            ),
            notificationService: LocalNotificationService(),
            attachmentImporter: AttachmentImportService(),
            allowsInsecureTransport: allowsInsecureTransport
        )
    }
}
