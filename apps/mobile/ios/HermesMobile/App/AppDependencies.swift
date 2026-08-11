import Foundation

public struct AppDependencies: Sendable {
    public let profileRepository: GatewayProfileRepository
    public let notificationService: any NotificationScheduling
    public let attachmentImporter: AttachmentImportService
    public let socketFactory: HermesGatewayTransport.SocketFactory
    public let urlSession: URLSession

    public init(
        profileRepository: GatewayProfileRepository,
        notificationService: any NotificationScheduling,
        attachmentImporter: AttachmentImportService,
        socketFactory: @escaping HermesGatewayTransport.SocketFactory = { URLSessionGatewaySocket(url: $0) },
        urlSession: URLSession = .shared
    ) {
        self.profileRepository = profileRepository
        self.notificationService = notificationService
        self.attachmentImporter = attachmentImporter
        self.socketFactory = socketFactory
        self.urlSession = urlSession
    }

    public static var live: AppDependencies {
        AppDependencies(
            profileRepository: GatewayProfileRepository(
                profileStore: UserDefaultsGatewayProfileStore(),
                credentialStore: KeychainCredentialStore(),
                sessionSelectionStore: UserDefaultsStoredSessionSelectionStore()
            ),
            notificationService: LocalNotificationService(),
            attachmentImporter: AttachmentImportService()
        )
    }
}