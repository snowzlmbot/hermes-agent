import Foundation
import Observation
import UniformTypeIdentifiers

public enum AppPhase: Equatable, Sendable {
    case loading
    case onboarding
    case connected
}

private struct PendingStoredSessionRoute: Sendable {
    let route: NotificationRoute
}

@MainActor
@Observable
public final class AppModel {
    public private(set) var phase: AppPhase = .loading
    public private(set) var profile: GatewayProfile?
    public private(set) var chatModel: ChatModel?
    public private(set) var gatewayRESTClient: GatewayRESTClient?
    public private(set) var oauthCapability: NativeOAuthCapability?
    public private(set) var oauthProviders: [NativeOAuthProvider] = []
    public private(set) var isConnecting = false
    public private(set) var isSceneActive = true
    public private(set) var selectedSessionID: String?
    public var errorMessage: String?

    @ObservationIgnored private let dependencies: AppDependencies
    @ObservationIgnored private let backgroundActivity = BackgroundActivityService()
    @ObservationIgnored private let nativeAuthenticationSession = NativeAuthenticationSession()
    @ObservationIgnored private var sceneRecoveryTask: Task<Void, Never>?
    @ObservationIgnored private var sceneRecoveryGeneration: UInt = 0
    @ObservationIgnored private var sessionSelectionGeneration: UInt = 0
    @ObservationIgnored private var profileGeneration: UInt = 0
    @ObservationIgnored private var pendingNotificationRoutes: [PendingStoredSessionRoute] = []
    @ObservationIgnored private var isConsumingNotificationRoutes = false
    @ObservationIgnored private var notificationAuthorizationGranted = false
    @ObservationIgnored private var hasSceneRecoveryError = false
    @ObservationIgnored private var eventReplayGuard: EventReplayGuard?
    @ObservationIgnored private var eventReplayProfileScope: String?

    var pendingNotificationRouteCount: Int {
        pendingNotificationRoutes.count
    }

    public var allowsInsecureTransport: Bool {
        dependencies.allowsInsecureTransport
    }

    public init(dependencies: AppDependencies = .live) {
        self.dependencies = dependencies
    }

    public func bootstrap(arguments: [String] = ProcessInfo.processInfo.arguments) async {
        if arguments.contains("--ui-smoke") {
            phase = .onboarding
            return
        }
        if arguments.contains("--ui-demo") {
            prepareDemo()
            return
        }

        do {
            guard let stored = try await dependencies.profileRepository.load() else {
                phase = .onboarding
                return
            }
            try await activate(profile: stored.profile, credentials: stored.credentials)
        } catch {
            errorMessage = String(localized: "error.restore.connection")
            phase = .onboarding
        }
    }

    public func connect(address: String, token: String, allowInsecure: Bool) async {
        guard !isConnecting else { return }
        isConnecting = true
        errorMessage = nil
        oauthCapability = nil
        oauthProviders = []
        defer { isConnecting = false }

        do {
            if allowInsecure && !dependencies.allowsInsecureTransport {
                throw GatewayEndpointError.insecureRemoteEndpoint
            }
            let allowCleartext = dependencies.allowsInsecureTransport && allowInsecure
            let endpoint = try GatewayEndpoint(rawValue: address, allowInsecureRemote: allowCleartext)
            if endpoint.baseURL.scheme != "https" && !allowCleartext {
                throw GatewayEndpointError.insecureRemoteEndpoint
            }
            let status = try await GatewayRESTClient.status(endpoint: endpoint, session: dependencies.urlSession)
            let cleanToken = token.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !cleanToken.isEmpty else {
                if status.authRequired {
                    guard endpoint.baseURL.scheme == "https" else {
                        errorMessage = String(localized: "error.oauth.unavailable")
                        return
                    }
                    oauthCapability = status.nativeOAuthCapability
                    if status.nativeOAuthCapability.supportsASWebAuthenticationSessionCallback {
                        oauthProviders = try await GatewayRESTClient.nativeOAuthProviders(
                            endpoint: endpoint,
                            session: dependencies.urlSession
                        )
                    }
                    return
                }
                errorMessage = String(localized: "error.token.required")
                return
            }
            let profile = GatewayProfile(
                name: String(localized: "profile.default.name"),
                endpoint: endpoint.baseURL.absoluteString,
                authMode: .token,
                allowInsecure: allowCleartext
            )
            let credentials = try GatewayCredentials(endpoint: profile.endpoint, token: cleanToken)
            try await dependencies.profileRepository.save(profile: profile, credentials: credentials)
            try await activate(profile: profile, credentials: credentials)
        } catch let endpointError as GatewayEndpointError {
            errorMessage = endpointMessage(endpointError)
        } catch {
            errorMessage = String(localized: "error.connection.failed")
        }
    }

    public func signInWithOAuth(address: String, provider: String) async {
        guard !isConnecting else { return }
        isConnecting = true
        errorMessage = nil
        defer { isConnecting = false }

        do {
            let endpoint = try GatewayEndpoint(rawValue: address)
            if endpoint.baseURL.scheme != "https" {
                throw GatewayEndpointError.insecureRemoteEndpoint
            }
            let status = try await GatewayRESTClient.status(
                endpoint: endpoint,
                session: dependencies.urlSession
            )
            let capability = status.nativeOAuthCapability
            oauthCapability = capability
            guard capability.supportsASWebAuthenticationSessionCallback else {
                errorMessage = String(localized: "error.oauth.unavailable")
                return
            }
            let providers = try await GatewayRESTClient.nativeOAuthProviders(
                endpoint: endpoint,
                session: dependencies.urlSession
            )
            oauthProviders = providers

            let cleanProvider = provider.trimmingCharacters(in: .whitespacesAndNewlines)
            let selected = providers.first { $0.name == cleanProvider }
                ?? (cleanProvider.isEmpty && providers.count == 1 ? providers[0] : nil)
            guard let selected else {
                errorMessage = String(localized: "error.oauth.provider.required")
                return
            }
            let request = try NativeAuthorizationRequest(endpoint: endpoint, provider: selected.name)
            let callbackURL = try await nativeAuthenticationSession.authenticate(request)
            let code = try request.authorizationCode(from: callbackURL)
            let tokens = try await GatewayRESTClient.exchangeNativeCode(
                endpoint: endpoint,
                code: code,
                verifier: request.verifier,
                session: dependencies.urlSession
            )
            let profile = GatewayProfile(
                name: String(localized: "profile.default.name"),
                endpoint: endpoint.baseURL.absoluteString,
                authMode: .oauth,
                allowInsecure: false
            )
            let credentials = GatewayCredentials.oauth(
                tokens,
                endpoint: profile.endpoint,
                profileID: profile.id
            )
            try await dependencies.profileRepository.save(profile: profile, credentials: credentials)
            try await activate(profile: profile, credentials: credentials)
        } catch NativeOAuthError.cancelled {
            return
        } catch let endpointError as GatewayEndpointError {
            errorMessage = endpointMessage(endpointError)
        } catch {
            errorMessage = String(localized: "error.oauth.failed")
        }
    }

    public func disconnectAndForget() async {
        invalidateSceneRecovery()
        invalidateNotificationRouting(clearPending: true)
        await chatModel?.disconnect()
        await gatewayRESTClient?.invalidate()
        do {
            try await dependencies.profileRepository.clear()
        } catch {
            errorMessage = String(localized: "error.credentials.clear")
            return
        }
        profile = nil
        chatModel = nil
        eventReplayGuard?.clear()
        eventReplayGuard = nil
        eventReplayProfileScope = nil
        gatewayRESTClient = nil
        selectedSessionID = nil
        oauthCapability = nil
        oauthProviders = []
        phase = .onboarding
    }

    public func setSceneActive(_ active: Bool) {
        guard active != isSceneActive else { return }
        isSceneActive = active
        if active {
            scheduleSceneRecovery()
        } else {
            invalidateSceneRecovery()
        }
    }

    func waitForSceneRecovery() async {
        await sceneRecoveryTask?.value
    }

    public func handleNotificationRoute(_ route: NotificationRoute) async {
        guard NotificationRouteMetadata.normalizedStoredSessionID(route.storedSessionID) != nil,
              NotificationRouteMetadata.normalizedProfileScope(route.profileScope) != nil else { return }
        pendingNotificationRoutes.append(PendingStoredSessionRoute(route: route))
        await consumePendingNotificationRoutesIfPossible()
    }

    public func createSession() async {
        guard let chatModel else { return }
        let generation = await beginSessionSelectionOperation()
        do {
            let active = try await chatModel.createSession(profileID: profile?.id ?? "default")
            guard isCurrentSessionSelectionOperation(generation) else { return }
            await commitSelectedSessionID(active.storedID)
        } catch {
            guard isCurrentSessionSelectionOperation(generation) else { return }
            errorMessage = String(localized: "error.session.create")
        }
    }

    public func sendMessage(_ text: String) async -> Bool {
        guard let chatModel else { return false }
        backgroundActivity.begin()
        do {
            try await chatModel.send(text: text)
            return true
        } catch {
            backgroundActivity.end()
            errorMessage = String(localized: "error.message.send")
            return false
        }
    }

    public func stopMessage() async {
        guard let chatModel else { return }
        do {
            try await chatModel.stop()
        } catch {
            errorMessage = String(localized: "error.message.stop")
        }
        backgroundActivity.end()
    }

    public func selectSession(_ storedID: String) async {
        guard let profile else { return }
        pendingNotificationRoutes.removeAll(keepingCapacity: true)
        await selectSession(
            storedID,
            expectedProfileScope: profile.sessionSelectionScope,
            expectedProfileGeneration: profileGeneration
        )
    }

    private func selectSession(
        _ storedID: String,
        expectedProfileScope: String,
        expectedProfileGeneration: UInt
    ) async {
        guard isCurrentProfileContext(
            scope: expectedProfileScope,
            generation: expectedProfileGeneration
        ), let chatModel else { return }
        let generation = await beginSessionSelectionOperation()
        guard isCurrentProfileContext(
            scope: expectedProfileScope,
            generation: expectedProfileGeneration,
            chatModel: chatModel
        ) else { return }
        if chatModel.state.storedSessionID == storedID {
            guard isCurrentSessionSelectionOperation(generation) else { return }
            await commitSelectedSessionID(storedID, expectedGeneration: generation)
            return
        }
        do {
            let active = try await chatModel.resume(
                storedSessionID: storedID,
                profileID: profile?.id ?? "default"
            )
            guard isCurrentSessionSelectionOperation(generation),
                  isCurrentProfileContext(
                      scope: expectedProfileScope,
                      generation: expectedProfileGeneration,
                      chatModel: chatModel
                  ) else { return }
            await commitSelectedSessionID(active.storedID, expectedGeneration: generation)
        } catch {
            guard isCurrentSessionSelectionOperation(generation),
                  isCurrentProfileContext(
                      scope: expectedProfileScope,
                      generation: expectedProfileGeneration,
                      chatModel: chatModel
                  ) else { return }
            errorMessage = String(localized: "error.session.resume")
        }
    }

    public func restoreSession(_ storedID: String) async {
        guard let chatModel, let profile else { return }
        let expectedScope = profile.sessionSelectionScope
        let expectedGeneration = profileGeneration
        do {
            try await chatModel.restoreArchivedSession(storedSessionID: storedID)
            guard isCurrentProfileContext(
                scope: expectedScope,
                generation: expectedGeneration,
                chatModel: chatModel
            ) else { return }
        } catch {
            guard isCurrentProfileContext(
                scope: expectedScope,
                generation: expectedGeneration,
                chatModel: chatModel
            ) else { return }
            errorMessage = String(localized: "error.session.restore")
        }
    }

    public func renameSession(_ storedID: String, title: String) async {
        guard let chatModel else { return }
        do {
            try await chatModel.rename(storedSessionID: storedID, title: title)
        } catch {
            errorMessage = String(localized: "error.session.rename")
        }
    }

    public func archiveSession(_ storedID: String) async {
        guard let chatModel else { return }
        do {
            try await chatModel.setArchived(true, storedSessionID: storedID)
            if selectedSessionID == storedID {
                if let nextStoredID = chatModel.sessions.first(where: { !$0.archived })?.storedID {
                    await selectSession(nextStoredID)
                } else {
                    _ = await beginSessionSelectionOperation()
                    await commitSelectedSessionID(nil)
                }
            }
        } catch {
            errorMessage = String(localized: "error.session.archive")
        }
    }

    public func setSessionPinned(_ pinned: Bool, storedID: String) async {
        guard let chatModel else { return }
        do {
            try await chatModel.setPinned(pinned, storedSessionID: storedID)
        } catch {
            errorMessage = String(localized: "error.session.pin")
        }
    }

    public func deleteSession(_ storedID: String) async {
        guard let chatModel else { return }
        do {
            try await chatModel.delete(storedSessionID: storedID)
            if selectedSessionID == storedID {
                if let nextStoredID = chatModel.sessions.first(where: { !$0.archived })?.storedID {
                    await selectSession(nextStoredID)
                } else {
                    _ = await beginSessionSelectionOperation()
                    await commitSelectedSessionID(nil)
                }
            }
        } catch {
            errorMessage = String(localized: "error.session.delete")
        }
    }

    public func attachPhoto(data: Data, contentType: UTType?) async {
        guard let chatModel else { return }
        let payload: AttachmentPayload
        do {
            payload = try await dependencies.attachmentImporter.imagePayload(
                data: data,
                contentType: contentType
            )
        } catch {
            errorMessage = String(localized: "error.attachment.import")
            return
        }
        do {
            try await chatModel.attach(payload)
        } catch {
            errorMessage = String(localized: "error.attachment.upload")
        }
    }

    public func attachFile(url: URL) async {
        guard let chatModel else { return }
        let payload: AttachmentPayload
        do {
            payload = try await dependencies.attachmentImporter.filePayload(at: url)
        } catch {
            errorMessage = String(localized: "error.attachment.import")
            return
        }
        do {
            try await chatModel.attach(payload)
        } catch {
            errorMessage = String(localized: "error.attachment.upload")
        }
    }

    public func reportAttachmentImportError() {
        errorMessage = String(localized: "error.attachment.import")
    }

    public func clearError() {
        errorMessage = nil
        hasSceneRecoveryError = false
    }

    private func consumePendingNotificationRoutesIfPossible() async {
        guard phase == .connected,
              profile != nil,
              chatModel?.isConnected == true,
              !isConsumingNotificationRoutes else { return }
        isConsumingNotificationRoutes = true
        defer { isConsumingNotificationRoutes = false }

        while phase == .connected,
              let currentProfile = profile,
              chatModel?.isConnected == true,
              !pendingNotificationRoutes.isEmpty {
            let pending = pendingNotificationRoutes.removeFirst()
            let expectedProfileScope = NotificationRouteMetadata.profileScope(
                for: currentProfile.sessionSelectionScope
            )
            guard pending.route.profileScope == expectedProfileScope else { continue }
            await selectSession(
                pending.route.storedSessionID,
                expectedProfileScope: currentProfile.sessionSelectionScope,
                expectedProfileGeneration: profileGeneration
            )
        }
    }

    private func isCurrentProfileContext(
        scope: String,
        generation: UInt,
        chatModel expectedChatModel: ChatModel? = nil
    ) -> Bool {
        guard generation == profileGeneration,
              profile?.sessionSelectionScope == scope else { return false }
        guard let expectedChatModel else { return true }
        guard let currentChatModel = chatModel else { return false }
        return currentChatModel === expectedChatModel
    }

    private func invalidateNotificationRouting(clearPending: Bool) {
        profileGeneration &+= 1
        sessionSelectionGeneration &+= 1
        notificationAuthorizationGranted = false
        if clearPending {
            pendingNotificationRoutes.removeAll(keepingCapacity: true)
        }
    }

    private func beginSessionSelectionOperation() async -> UInt {
        sceneRecoveryGeneration &+= 1
        let recoveryTask = sceneRecoveryTask
        recoveryTask?.cancel()
        sceneRecoveryTask = nil
        await recoveryTask?.value
        sessionSelectionGeneration &+= 1
        return sessionSelectionGeneration
    }

    private func isCurrentSessionSelectionOperation(_ generation: UInt) -> Bool {
        generation == sessionSelectionGeneration
    }

    private func commitSelectedSessionID(
        _ storedSessionID: String?,
        expectedGeneration: UInt? = nil
    ) async {
        if let expectedGeneration,
           !isCurrentSessionSelectionOperation(expectedGeneration) { return }
        selectedSessionID = storedSessionID
        guard let profile = profile else { return }
        await dependencies.profileRepository.saveStoredSessionID(
            storedSessionID,
            for: profile
        )
    }

    private func scheduleSceneRecovery() {
        guard phase == .connected,
              sceneRecoveryTask == nil,
              chatModel != nil,
              profile != nil else { return }
        sceneRecoveryGeneration &+= 1
        let recoveryGeneration = sceneRecoveryGeneration
        let selectionGeneration = sessionSelectionGeneration
        sceneRecoveryTask = Task { [weak self] in
            guard let self else { return }
            await self.performSceneRecovery(
                recoveryGeneration: recoveryGeneration,
                selectionGeneration: selectionGeneration
            )
        }
    }

    private func invalidateSceneRecovery() {
        sceneRecoveryGeneration &+= 1
        sceneRecoveryTask?.cancel()
        sceneRecoveryTask = nil
    }

    private func performSceneRecovery(
        recoveryGeneration: UInt,
        selectionGeneration: UInt
    ) async {
        defer {
            if recoveryGeneration == sceneRecoveryGeneration {
                sceneRecoveryTask = nil
            }
        }
        guard recoveryGeneration == sceneRecoveryGeneration,
              selectionGeneration == sessionSelectionGeneration,
              isSceneActive,
              let profile,
              let chatModel else { return }

        let persistedSessionID = await dependencies.profileRepository.loadStoredSessionID(
            for: profile
        )
        guard recoveryGeneration == sceneRecoveryGeneration,
              selectionGeneration == sessionSelectionGeneration,
              isSceneActive else { return }
        let storedSessionID = selectedSessionID ?? persistedSessionID

        do {
            let active = try await chatModel.reconnectAndRestore(
                storedSessionID: storedSessionID,
                profileID: profile.id
            )
            guard recoveryGeneration == sceneRecoveryGeneration,
                  selectionGeneration == sessionSelectionGeneration,
                  isSceneActive,
                  self.chatModel === chatModel else { return }
            await commitSelectedSessionID(active.storedID)
            if hasSceneRecoveryError,
               errorMessage == String(localized: "error.restore.connection") {
                errorMessage = nil
            }
            hasSceneRecoveryError = false
        } catch is CancellationError {
            return
        } catch {
            guard recoveryGeneration == sceneRecoveryGeneration,
                  selectionGeneration == sessionSelectionGeneration,
                  isSceneActive else { return }
            hasSceneRecoveryError = true
            errorMessage = String(localized: "error.restore.connection")
        }
    }

    private func activate(profile: GatewayProfile, credentials: GatewayCredentials) async throws {
        let endpoint = try GatewayEndpoint(
            rawValue: profile.endpoint,
            allowInsecureRemote: profile.allowInsecure
        )
        invalidateNotificationRouting(clearPending: false)
        let repository = dependencies.profileRepository
        let restClient = GatewayRESTClient(
            endpoint: endpoint,
            credentials: credentials,
            session: dependencies.urlSession,
            persistCredentials: { updated in
                try await repository.save(profile: profile, credentials: updated)
            }
        )
        let ticketProvider: @Sendable () async throws -> String = {
            try await restClient.freshWebSocketTicket()
        }
        let transportAuth: GatewayAuth
        switch credentials.auth {
        case .token:
            transportAuth = .ticketProvider(ticketProvider)
        case .oauth:
            transportAuth = .oauthTicketProvider(ticketProvider)
        }
        let transport = HermesGatewayTransport(
            endpoint: endpoint,
            auth: transportAuth,
            socketFactory: dependencies.socketFactory
        )
        let replayGuard: EventReplayGuard
        if eventReplayProfileScope == profile.sessionSelectionScope, let existing = eventReplayGuard {
            replayGuard = existing
        } else {
            eventReplayGuard?.clear()
            replayGuard = EventReplayGuard()
            replayGuard.activate(profileScope: profile.sessionSelectionScope)
            eventReplayGuard = replayGuard
            eventReplayProfileScope = profile.sessionSelectionScope
        }
        let chatModel = ChatModel(
            transport: transport,
            sessionMutationClient: restClient,
            eventReplayGuard: replayGuard,
            profileScope: profile.sessionSelectionScope
        )
        configureSignals(for: chatModel, profile: profile)

        let storedSessionID = await repository.loadStoredSessionID(for: profile)
        try await chatModel.connect()
        let active = try await chatModel.restoreSession(
            storedSessionID: storedSessionID,
            profileID: profile.id
        )
        await repository.saveStoredSessionID(active.storedID, for: profile)

        self.profile = profile
        self.gatewayRESTClient = restClient
        self.chatModel = chatModel
        self.selectedSessionID = active.storedID
        self.phase = .connected
        await consumePendingNotificationRoutesIfPossible()
        notificationAuthorizationGranted = await dependencies.notificationService.requestAuthorization()
    }

    private func canScheduleNotification(_ route: NotificationRoute) -> Bool {
        NotificationDeliveryPolicy.shouldSchedule(
            isSceneActive: isSceneActive,
            isAuthorized: notificationAuthorizationGranted,
            route: route
        )
    }

    private func configureSignals(for chatModel: ChatModel, profile: GatewayProfile) {
        let notificationProfileScope = NotificationRouteMetadata.profileScope(
            for: profile.sessionSelectionScope
        )
        chatModel.invalidStoredSessionHandler = { [weak self, weak chatModel] in
            guard let self, let chatModel else { return }
            await self.dependencies.profileRepository.saveStoredSessionID(nil, for: profile)
            if self.chatModel == nil || self.chatModel === chatModel {
                self.selectedSessionID = nil
            }
        }
        chatModel.signalHandler = { [weak self, weak chatModel] signal in
            guard let self, let chatModel else { return }
            switch signal {
            case .messageCompleted(let storedSessionID):
                self.backgroundActivity.end()
                guard !self.isSceneActive, let storedSessionID else { return }
                let title = chatModel.sessions.first(where: { $0.storedID == storedSessionID })?.displayTitle
                    ?? String(localized: "session.new")
                let route = NotificationRoute(
                    storedSessionID: storedSessionID,
                    profileScope: notificationProfileScope
                )
                guard self.canScheduleNotification(route) else { return }
                Task {
                    await self.dependencies.notificationService.scheduleCompletion(
                        sessionTitle: title,
                        route: route
                    )
                }
            case .approvalRequired(let storedSessionID):
                guard !self.isSceneActive, let storedSessionID else { return }
                let route = NotificationRoute(
                    storedSessionID: storedSessionID,
                    profileScope: notificationProfileScope
                )
                guard self.canScheduleNotification(route) else { return }
                Task { await self.dependencies.notificationService.scheduleApproval(route: route) }
            case .inputRequired(let storedSessionID):
                guard !self.isSceneActive, let storedSessionID else { return }
                let route = NotificationRoute(
                    storedSessionID: storedSessionID,
                    profileScope: notificationProfileScope
                )
                guard self.canScheduleNotification(route) else { return }
                Task { await self.dependencies.notificationService.scheduleInput(route: route) }
            case .sessionSelectionChanged(let storedID):
                guard self.chatModel === chatModel else { return }
                let generation = self.sessionSelectionGeneration
                Task { await self.commitSelectedSessionID(storedID, expectedGeneration: generation) }
            case .sessionsChanged:
                Task {
                    do {
                        try await chatModel.loadSessions()
                    } catch {
                        self.errorMessage = String(localized: "error.sessions.load")
                    }
                }
            }
        }
    }

    private func prepareDemo() {
        do {
            let endpoint = try GatewayEndpoint(rawValue: "https://127.0.0.1")
            let transport = HermesGatewayTransport(
                endpoint: endpoint,
                auth: .token("demo"),
                socketFactory: dependencies.socketFactory
            )
            let chatModel = ChatModel(transport: transport)
            chatModel.seedDemo()
            self.chatModel = chatModel
            self.selectedSessionID = "demo"
            self.phase = .connected
        } catch {
            self.errorMessage = String(localized: "error.demo")
            self.phase = .onboarding
        }
    }

    private func endpointMessage(_ error: GatewayEndpointError) -> String {
        switch error {
        case .required: return String(localized: "error.endpoint.required")
        case .unsupportedScheme: return String(localized: "error.endpoint.scheme")
        case .credentialsInURL: return String(localized: "error.endpoint.credentials")
        case .queryOrFragmentNotAllowed: return String(localized: "error.endpoint.query")
        case .insecureRemoteEndpoint: return String(localized: "error.endpoint.insecure")
        case .malformed: return String(localized: "error.endpoint.malformed")
        }
    }
}
