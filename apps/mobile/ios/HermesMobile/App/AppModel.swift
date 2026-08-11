import Foundation
import Observation
import UniformTypeIdentifiers

public enum AppPhase: Equatable, Sendable {
    case loading
    case onboarding
    case connected
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
    public var selectedSessionID: String?
    public var errorMessage: String?

    @ObservationIgnored private let dependencies: AppDependencies
    @ObservationIgnored private let backgroundActivity = BackgroundActivityService()
    @ObservationIgnored private let nativeAuthenticationSession = NativeAuthenticationSession()

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
            let endpoint = try GatewayEndpoint(rawValue: address, allowInsecureRemote: allowInsecure)
            let status = try await GatewayRESTClient.status(endpoint: endpoint, session: dependencies.urlSession)
            let cleanToken = token.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !cleanToken.isEmpty else {
                if status.authRequired {
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
                allowInsecure: allowInsecure
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

    public func signInWithOAuth(address: String, provider: String, allowInsecure: Bool) async {
        guard !isConnecting else { return }
        isConnecting = true
        errorMessage = nil
        defer { isConnecting = false }

        do {
            let endpoint = try GatewayEndpoint(rawValue: address, allowInsecureRemote: allowInsecure)
            let status = try await GatewayRESTClient.status(endpoint: endpoint, session: dependencies.urlSession)
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
                allowInsecure: allowInsecure
            )
            let credentials = GatewayCredentials.oauth(tokens, endpoint: profile.endpoint)
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
        await chatModel?.disconnect()
        do {
            try await dependencies.profileRepository.clear()
        } catch {
            errorMessage = String(localized: "error.credentials.clear")
        }
        profile = nil
        chatModel = nil
        gatewayRESTClient = nil
        selectedSessionID = nil
        oauthCapability = nil
        oauthProviders = []
        phase = .onboarding
    }

    public func setSceneActive(_ active: Bool) {
        isSceneActive = active
    }

    public func createSession() async {
        guard let chatModel else { return }
        do {
            let active = try await chatModel.createSession(profileID: profile?.id ?? "default")
            selectedSessionID = active.storedID
        } catch {
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
        guard let chatModel else { return }
        selectedSessionID = storedID
        if chatModel.state.storedSessionID == storedID { return }
        do {
            _ = try await chatModel.resume(storedSessionID: storedID, profileID: profile?.id ?? "default")
        } catch {
            errorMessage = String(localized: "error.session.resume")
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
                selectedSessionID = chatModel.sessions.first?.storedID
                if let selectedSessionID { await selectSession(selectedSessionID) }
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
                selectedSessionID = chatModel.sessions.first?.storedID
                if let selectedSessionID { await selectSession(selectedSessionID) }
            }
        } catch {
            errorMessage = String(localized: "error.session.delete")
        }
    }

    public func attachPhoto(data: Data, contentType: UTType?) async {
        guard let chatModel else { return }
        do {
            let payload = try await dependencies.attachmentImporter.imagePayload(
                data: data,
                contentType: contentType
            )
            try await chatModel.attach(payload)
        } catch {
            errorMessage = String(localized: "error.attachment.upload")
        }
    }

    public func attachFile(url: URL) async {
        guard let chatModel else { return }
        do {
            let payload = try await dependencies.attachmentImporter.filePayload(at: url)
            try await chatModel.attach(payload)
        } catch {
            errorMessage = String(localized: "error.attachment.upload")
        }
    }

    public func clearError() {
        errorMessage = nil
    }

    private func activate(profile: GatewayProfile, credentials: GatewayCredentials) async throws {
        let endpoint = try GatewayEndpoint(
            rawValue: profile.endpoint,
            allowInsecureRemote: profile.allowInsecure
        )
        let repository = dependencies.profileRepository
        let restClient = GatewayRESTClient(
            endpoint: endpoint,
            credentials: credentials,
            session: dependencies.urlSession,
            persistCredentials: { updated in
                try await repository.save(profile: profile, credentials: updated)
            }
        )
        let transportAuth: GatewayAuth
        switch credentials.auth {
        case .token(let token):
            transportAuth = .token(token)
        case .oauth:
            transportAuth = .ticketProvider { try await restClient.freshWebSocketTicket() }
        }
        let transport = HermesGatewayTransport(
            endpoint: endpoint,
            auth: transportAuth,
            socketFactory: dependencies.socketFactory
        )
        let chatModel = ChatModel(
            transport: transport,
            sessionMutationClient: restClient
        )
        configureSignals(for: chatModel)

        try await chatModel.connect()
        try await chatModel.loadSessions()
        if let first = chatModel.sessions.first {
            _ = try await chatModel.resume(storedSessionID: first.storedID, profileID: profile.id)
            selectedSessionID = first.storedID
        } else {
            let active = try await chatModel.createSession(profileID: profile.id)
            selectedSessionID = active.storedID
        }

        self.profile = profile
        self.gatewayRESTClient = restClient
        self.chatModel = chatModel
        self.phase = .connected
        _ = await dependencies.notificationService.requestAuthorization()
    }

    private func configureSignals(for chatModel: ChatModel) {
        chatModel.signalHandler = { [weak self, weak chatModel] signal in
            guard let self, let chatModel else { return }
            switch signal {
            case .messageCompleted(let sessionID):
                self.backgroundActivity.end()
                guard !self.isSceneActive else { return }
                let title = chatModel.sessions.first(where: { $0.storedID == chatModel.state.storedSessionID })?.displayTitle
                    ?? String(localized: "session.new")
                Task {
                    await self.dependencies.notificationService.scheduleCompletion(
                        sessionTitle: title,
                        sessionID: sessionID
                    )
                }
            case .approvalRequired(let sessionID):
                guard !self.isSceneActive, let sessionID else { return }
                Task { await self.dependencies.notificationService.scheduleApproval(sessionID: sessionID) }
            case .inputRequired(let sessionID):
                guard !self.isSceneActive, let sessionID else { return }
                Task { await self.dependencies.notificationService.scheduleInput(sessionID: sessionID) }
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
            let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1")
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