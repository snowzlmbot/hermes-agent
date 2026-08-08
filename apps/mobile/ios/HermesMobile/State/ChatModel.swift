import Foundation
import Observation

public enum ChatModelError: Error, Equatable, Sendable {
    case sessionRequired
    case emptyMessage
    case emptyTitle
    case archiveUnavailable
}

@MainActor
@Observable
public final class ChatModel {
    public private(set) var state: ChatState
    public private(set) var sessions: [SessionSummary]
    public private(set) var isConnected: Bool
    public private(set) var isLoadingSessions: Bool
    public private(set) var selectedModelID: String
    public private(set) var selectedProviderID: String
    public private(set) var reasoningEffort: String
    public private(set) var modelCatalog: ModelCatalog

    @ObservationIgnored public var signalHandler: (@MainActor (ChatSignal) -> Void)?
    @ObservationIgnored private let transport: HermesGatewayTransport
    @ObservationIgnored private let archiveStore: (any SessionArchiveStore)?
    @ObservationIgnored private let sessionMutationClient: (any SessionMutationClient)?
    @ObservationIgnored private var eventTask: Task<Void, Never>?

    public init(
        transport: HermesGatewayTransport,
        archiveStore: (any SessionArchiveStore)? = nil,
        sessionMutationClient: (any SessionMutationClient)? = nil,
        state: ChatState = .empty,
        sessions: [SessionSummary] = []
    ) {
        self.transport = transport
        self.archiveStore = archiveStore
        self.sessionMutationClient = sessionMutationClient
        self.state = state
        self.sessions = sessions
        self.isConnected = false
        self.isLoadingSessions = false
        self.selectedModelID = ""
        self.selectedProviderID = ""
        self.reasoningEffort = ""
        self.modelCatalog = ModelCatalog()
    }


    public func connect() async throws {
        try await transport.connect()
        isConnected = true
        startEventLoop()
    }

    public func disconnect() async {
        eventTask?.cancel()
        eventTask = nil
        await transport.disconnect()
        isConnected = false
    }

    public func setRuntimeSession(runtimeID: String, storedID: String) {
        ChatReducer.reduce(
            &state,
            action: .sessionReady(runtimeID: runtimeID, storedID: storedID, messages: [])
        )
        modelCatalog = ModelCatalog()
        selectedModelID = ""
        selectedProviderID = ""
        reasoningEffort = ""
    }

    public func loadSessions(includeArchived: Bool = false) async throws {
        isLoadingSessions = true
        defer { isLoadingSessions = false }
        let result = try await transport.request(
            GatewayMethod.sessionList,
            params: [
                "limit": .number(100),
                "source": .string("mobile")
            ]
        )
        var parsed = GatewayProtocol.parseSessionList(result: result)
        if !includeArchived {
            parsed.removeAll { $0.archived }
        }
        if let archiveStore {
            var visible: [SessionSummary] = []
            for session in parsed {
                if !(await archiveStore.isArchived(session.storedID)) {
                    visible.append(session)
                }
            }
            parsed = visible
        }
        sessions = parsed
        sortSessionsForDisplay()
    }

    @discardableResult
    public func createSession(profileID: String = "default") async throws -> ActiveSession {
        let result = try await transport.request(
            GatewayMethod.sessionCreate,
            params: [
                "source": .string("mobile"),
                "profile": .string(profileID),
                "cols": .number(80)
            ]
        )
        let active = try GatewayProtocol.parseActiveSession(result: result)
        applyActiveSession(active)
        insertOrUpdateSummary(
            SessionSummary(storedID: active.storedID, startedAt: Date().timeIntervalSince1970)
        )
        return active
    }

    @discardableResult
    public func resume(storedSessionID: String, profileID: String = "default") async throws -> ActiveSession {
        let result = try await transport.request(
            GatewayMethod.sessionResume,
            params: [
                "session_id": .string(storedSessionID),
                "source": .string("mobile"),
                "profile": .string(profileID),
                "cols": .number(80),
                "eager_build": .bool(true)
            ]
        )
        let active = try GatewayProtocol.parseActiveSession(result: result)
        applyActiveSession(active)
        return active
    }

    public func send(text: String) async throws {
        let cleanText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanText.isEmpty else { throw ChatModelError.emptyMessage }
        guard let runtimeID = state.runtimeSessionID else { throw ChatModelError.sessionRequired }
        let previousMessages = state.messages
        ChatReducer.reduce(&state, action: .userSubmitted(text: cleanText))
        do {
            _ = try await transport.request(
                GatewayMethod.promptSubmit,
                params: ["session_id": .string(runtimeID), "text": .string(cleanText)]
            )
        } catch {
            state.messages = previousMessages
            throw error
        }
    }

    public func stop() async throws {
        guard let runtimeID = state.runtimeSessionID else { throw ChatModelError.sessionRequired }
        _ = try await transport.request(
            GatewayMethod.sessionInterrupt,
            params: ["session_id": .string(runtimeID)]
        )
        ChatReducer.reduce(&state, action: .streamingChanged(false))
    }

    public func loadModelOptions() async throws {
        guard let runtimeID = state.runtimeSessionID else { throw ChatModelError.sessionRequired }
        let result = try await transport.request(
            GatewayMethod.modelOptions,
            params: ["session_id": .string(runtimeID)]
        )
        guard state.runtimeSessionID == runtimeID else { return }
        let catalog = GatewayProtocol.parseModelOptions(result: result)
        modelCatalog = catalog
        selectedModelID = catalog.currentModel
        selectedProviderID = catalog.currentProvider
    }

    public func selectModel(_ option: ModelOption) async throws {
        guard let runtimeID = state.runtimeSessionID else { throw ChatModelError.sessionRequired }
        _ = try await transport.request(
            GatewayMethod.configSet,
            params: [
                "session_id": .string(runtimeID),
                "key": .string("model"),
                "value": .string("\(option.modelID) --provider \(option.providerID) --session")
            ]
        )
        guard state.runtimeSessionID == runtimeID else { return }
        selectedModelID = option.modelID
        selectedProviderID = option.providerID
        modelCatalog = ModelCatalog(
            currentModel: option.modelID,
            currentProvider: option.providerID,
            providers: modelCatalog.providers
        )
    }

    public func setReasoningEffort(_ effort: String) async throws {
        guard let runtimeID = state.runtimeSessionID else { throw ChatModelError.sessionRequired }
        let normalized = effort.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        _ = try await transport.request(
            GatewayMethod.configSet,
            params: [
                "session_id": .string(runtimeID),
                "key": .string("reasoning"),
                "value": .string(normalized)
            ]
        )
        guard state.runtimeSessionID == runtimeID else { return }
        reasoningEffort = normalized
    }

    public func renameCurrentSession(_ title: String) async throws {
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanTitle.isEmpty else { throw ChatModelError.emptyTitle }
        guard let runtimeID = state.runtimeSessionID else { throw ChatModelError.sessionRequired }
        _ = try await transport.request(
            GatewayMethod.sessionTitle,
            params: ["session_id": .string(runtimeID), "title": .string(cleanTitle)]
        )
        if let storedID = state.storedSessionID {
            updateSummary(storedID: storedID) { summary in
                var updated = summary
                updated.title = cleanTitle
                return updated
            }
        }
    }

    public func rename(storedSessionID: String, title: String) async throws {
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanTitle.isEmpty else { throw ChatModelError.emptyTitle }
        if storedSessionID == state.storedSessionID, state.runtimeSessionID != nil {
            try await renameCurrentSession(cleanTitle)
        } else if let sessionMutationClient {
            try await sessionMutationClient.patchSession(
                SessionMutation(storedID: storedSessionID, title: cleanTitle)
            )
            updateSummary(storedID: storedSessionID) { summary in
                var updated = summary
                updated.title = cleanTitle
                return updated
            }
        } else {
            throw ChatModelError.archiveUnavailable
        }
    }

    public func delete(storedSessionID: String) async throws {
        if let sessionMutationClient {
            try await sessionMutationClient.deleteSession(storedSessionID)
        } else {
            throw ChatModelError.archiveUnavailable
        }
        sessions.removeAll { $0.storedID == storedSessionID }
        if state.storedSessionID == storedSessionID {
            state = .empty
        }
    }

    public func setArchived(_ archived: Bool, storedSessionID: String) async throws {
        if let sessionMutationClient {
            try await sessionMutationClient.patchSession(
                SessionMutation(storedID: storedSessionID, archived: archived)
            )
        } else if let archiveStore {
            try await archiveStore.setArchived(archived, storedID: storedSessionID)
        } else {
            throw ChatModelError.archiveUnavailable
        }

        updateSummary(storedID: storedSessionID) { summary in
            var updated = summary
            updated.archived = archived
            return updated
        }
        if archived {
            sessions.removeAll { $0.storedID == storedSessionID }
            if state.storedSessionID == storedSessionID { state = .empty }
        }
    }

    public func setPinned(_ pinned: Bool, storedSessionID: String) async throws {
        guard let sessionMutationClient else {
            throw ChatModelError.archiveUnavailable
        }
        try await sessionMutationClient.patchSession(
            SessionMutation(storedID: storedSessionID, pinned: pinned)
        )
        updateSummary(storedID: storedSessionID) { summary in
            var updated = summary
            updated.pinned = pinned
            return updated
        }
    }

    public func attach(_ attachment: AttachmentPayload) async throws {
        guard let runtimeID = state.runtimeSessionID else { throw ChatModelError.sessionRequired }
        _ = try await transport.request(
            attachment.method,
            params: try attachment.rpcParameters(sessionID: runtimeID)
        )
    }

    public func respondToApproval(_ choice: ApprovalChoice) async throws {
        guard let runtimeID = state.runtimeSessionID else { throw ChatModelError.sessionRequired }
        _ = try await transport.request(
            GatewayMethod.approvalRespond,
            params: ["session_id": .string(runtimeID), "choice": .string(choice.rawValue)]
        )
        ChatReducer.reduce(&state, action: .approvalResolved)
    }

    public func respondToClarify(requestID: String, answer: String) async throws {
        _ = try await transport.request(
            GatewayMethod.clarifyRespond,
            params: ["request_id": .string(requestID), "answer": .string(answer)]
        )
        ChatReducer.reduce(&state, action: .clarifyResolved)
    }

    public func respondToSecret(requestID: String, value: String) async throws {
        _ = try await transport.request(
            GatewayMethod.secretRespond,
            params: ["request_id": .string(requestID), "value": .string(value)]
        )
        ChatReducer.reduce(&state, action: .secretResolved)
    }

    public func respondToSudo(requestID: String, password: String) async throws {
        _ = try await transport.request(
            GatewayMethod.sudoRespond,
            params: ["request_id": .string(requestID), "password": .string(password)]
        )
        ChatReducer.reduce(&state, action: .sudoResolved)
    }

    public func seedDemo() {
        sessions = [
            SessionSummary(
                storedID: "demo",
                title: String(localized: "demo.session.title"),
                preview: String(localized: "demo.welcome"),
                startedAt: Date().timeIntervalSince1970,
                lastActive: Date().timeIntervalSince1970,
                messageCount: 1,
                source: "mobile"
            )
        ]
        ChatReducer.reduce(
            &state,
            action: .sessionReady(
                runtimeID: "demo-runtime",
                storedID: "demo",
                messages: [ChatMessageRecord(role: .assistant, text: String(localized: "demo.welcome"))]
            )
        )
        let demoOption = ModelOption(
            providerID: "demo",
            providerName: "Hermes",
            modelID: "demo-model",
            supportsReasoning: true
        )
        modelCatalog = ModelCatalog(
            currentModel: demoOption.modelID,
            currentProvider: demoOption.providerID,
            providers: [ModelProviderOption(id: "demo", name: "Hermes", models: [demoOption])]
        )
        selectedModelID = demoOption.modelID
        selectedProviderID = demoOption.providerID
        reasoningEffort = "medium"
    }

    private func applyActiveSession(_ active: ActiveSession) {
        ChatReducer.reduce(
            &state,
            action: .sessionReady(
                runtimeID: active.runtimeID,
                storedID: active.storedID,
                messages: active.messages
            )
        )
        modelCatalog = ModelCatalog(
            currentModel: active.model,
            currentProvider: active.provider
        )
        selectedModelID = active.model
        selectedProviderID = active.provider
        reasoningEffort = active.reasoningEffort
    }

    private func startEventLoop() {
        guard eventTask == nil else { return }
        eventTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled, let event = await transport.nextEvent() {
                consume(event)
            }
        }
    }

    private func consume(_ event: GatewayEvent) {
        let payload = event.payload?.object ?? [:]
        switch event.type {
        case .gatewayReady:
            ChatReducer.reduce(&state, action: .statusUpdated(String(localized: "connection.connected")))
        case .sessionInfo:
            if let running = payload["running"]?.boolValue {
                ChatReducer.reduce(&state, action: .streamingChanged(running))
            }
            if let storedID = payload["stored_session_id"]?.stringValue,
               let runtimeID = event.sessionID,
               state.runtimeSessionID == runtimeID {
                state.storedSessionID = storedID
            }
        case .messageStart:
            ChatReducer.reduce(&state, action: .messageStarted(sessionID: event.sessionID))
        case .messageDelta:
            ChatReducer.reduce(
                &state,
                action: .messageDelta(sessionID: event.sessionID, text: payload["text"]?.stringValue ?? "")
            )
        case .messageInterim:
            ChatReducer.reduce(
                &state,
                action: .messageInterim(sessionID: event.sessionID, text: payload["text"]?.stringValue ?? "")
            )
        case .messageComplete:
            let status = MessageCompletionStatus(rawValue: payload["status"]?.stringValue ?? "complete") ?? .complete
            ChatReducer.reduce(
                &state,
                action: .messageCompleted(
                    sessionID: event.sessionID,
                    text: payload["text"]?.stringValue ?? "",
                    status: status
                )
            )
            signalHandler?(.messageCompleted(sessionID: event.sessionID))
        case .reasoningDelta, .thinkingDelta:
            ChatReducer.reduce(
                &state,
                action: .reasoningDelta(sessionID: event.sessionID, text: payload["text"]?.stringValue ?? "")
            )
        case .toolStart:
            let id = payload["tool_id"]?.stringValue
                ?? payload["tool_call_id"]?.stringValue
                ?? "tool-\(state.tools.count)"
            let activity = ToolActivity(
                id: id,
                name: payload["name"]?.stringValue ?? String(localized: "tool.generic"),
                context: payload["context"]?.stringValue
                    ?? payload["preview"]?.stringValue
                    ?? payload["args_text"]?.stringValue
                    ?? ""
            )
            ChatReducer.reduce(&state, action: .toolStarted(sessionID: event.sessionID, activity: activity))
        case .toolProgress:
            let id = payload["tool_id"]?.stringValue ?? payload["tool_call_id"]?.stringValue ?? ""
            ChatReducer.reduce(
                &state,
                action: .toolProgressed(
                    sessionID: event.sessionID,
                    id: id,
                    text: payload["text"]?.stringValue ?? payload["progress"]?.stringValue ?? ""
                )
            )
        case .toolComplete:
            let id = payload["tool_id"]?.stringValue ?? payload["tool_call_id"]?.stringValue ?? ""
            ChatReducer.reduce(
                &state,
                action: .toolCompleted(
                    sessionID: event.sessionID,
                    id: id,
                    result: payload["result"]?.safeDisplayText ?? ""
                )
            )
        case .approvalRequest:
            let choices = (payload["choices"]?.array ?? []).compactMap { value in
                value.stringValue.flatMap(ApprovalChoice.init(rawValue:))
            }
            let prompt = ApprovalPrompt(
                command: payload["command"]?.stringValue ?? "",
                description: payload["description"]?.stringValue ?? String(localized: "approval.description"),
                choices: choices.isEmpty ? [.once, .deny] : choices
            )
            ChatReducer.reduce(&state, action: .approvalRequested(sessionID: event.sessionID, prompt: prompt))
            signalHandler?(.approvalRequired(sessionID: event.sessionID))
        case .clarifyRequest:
            let prompt = ClarifyPrompt(
                requestID: payload["request_id"]?.stringValue ?? "",
                question: payload["question"]?.stringValue ?? String(localized: "clarify.question"),
                choices: (payload["choices"]?.array ?? []).compactMap(\.stringValue),
                allowsMultipleSelection: payload["multi_select"]?.boolValue ?? false
            )
            ChatReducer.reduce(&state, action: .clarifyRequested(sessionID: event.sessionID, prompt: prompt))
            signalHandler?(.inputRequired(sessionID: event.sessionID))
        case .clarifyExpire:
            ChatReducer.reduce(&state, action: .clarifyResolved)
        case .secretRequest:
            let prompt = SecretPrompt(
                requestID: payload["request_id"]?.stringValue ?? "",
                envVar: payload["env_var"]?.stringValue ?? "",
                question: payload["prompt"]?.stringValue ?? String(localized: "secret.question")
            )
            ChatReducer.reduce(&state, action: .secretRequested(sessionID: event.sessionID, prompt: prompt))
            signalHandler?(.inputRequired(sessionID: event.sessionID))
        case .secretExpire:
            ChatReducer.reduce(&state, action: .secretResolved)
        case .sudoRequest:
            ChatReducer.reduce(
                &state,
                action: .sudoRequested(
                    sessionID: event.sessionID,
                    requestID: payload["request_id"]?.stringValue ?? ""
                )
            )
            signalHandler?(.inputRequired(sessionID: event.sessionID))
        case .sudoExpire:
            ChatReducer.reduce(&state, action: .sudoResolved)
        case .error:
            ChatReducer.reduce(&state, action: .failed(String(localized: "error.gateway")))
        case .sessionsChanged:
            signalHandler?(.sessionsChanged)
        case .sessionTitle:
            if let storedID = payload["session_id"]?.stringValue ?? event.sessionID,
               let title = payload["title"]?.stringValue {
                updateSummary(storedID: storedID) { summary in
                    var updated = summary
                    updated.title = title
                    return updated
                }
            }
        case .statusUpdate:
            ChatReducer.reduce(
                &state,
                action: .statusUpdated(payload["text"]?.stringValue ?? payload["status"]?.stringValue ?? "")
            )
        case .unknown:
            break
        }
    }

    private func insertOrUpdateSummary(_ summary: SessionSummary) {
        sessions.removeAll { $0.storedID == summary.storedID }
        sessions.append(summary)
        sortSessionsForDisplay()
    }

    private func updateSummary(
        storedID: String,
        transform: (SessionSummary) -> SessionSummary
    ) {
        guard let index = sessions.firstIndex(where: { $0.storedID == storedID }) else { return }
        sessions[index] = transform(sessions[index])
        sortSessionsForDisplay()
    }

    private func sortSessionsForDisplay() {
        sessions.sort { lhs, rhs in
            if lhs.pinned != rhs.pinned { return lhs.pinned && !rhs.pinned }
            let lhsActivity = max(lhs.lastActive, lhs.startedAt)
            let rhsActivity = max(rhs.lastActive, rhs.startedAt)
            if lhsActivity != rhsActivity { return lhsActivity > rhsActivity }
            return lhs.storedID < rhs.storedID
        }
    }
}

private extension JSONValue {
    var safeDisplayText: String {
        switch self {
        case .string(let value): return String(value.prefix(2_000))
        case .number(let value): return String(value)
        case .bool(let value): return String(value)
        case .null: return ""
        case .array, .object:
            guard let data = try? JSONEncoder().encode(self),
                  let text = String(data: data, encoding: .utf8) else { return "" }
            return String(text.prefix(2_000))
        }
    }
}