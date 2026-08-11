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
    public private(set) var isLoadingModelOptions: Bool
    public private(set) var pendingModelConfirmation: PendingModelConfirmation?
    public private(set) var controlErrorMessage: String?

    @ObservationIgnored public var signalHandler: (@MainActor (ChatSignal) -> Void)?
    @ObservationIgnored private let transport: HermesGatewayTransport
    @ObservationIgnored private let archiveStore: (any SessionArchiveStore)?
    @ObservationIgnored private let sessionMutationClient: (any SessionMutationClient)?
    @ObservationIgnored private var eventTask: Task<Void, Never>?
    @ObservationIgnored private var connectionOperationGeneration: UInt = 0
    @ObservationIgnored private var sessionOperationGeneration: UInt = 0
    @ObservationIgnored private var sessionListOperationGeneration: UInt = 0
    @ObservationIgnored private var modelControlOperationGeneration: UInt = 0

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
        self.isLoadingModelOptions = false
        self.pendingModelConfirmation = nil
        self.controlErrorMessage = nil
    }


    public func connect() async throws {
        let generation = beginConnectionOperation()
        try await transport.connect()
        guard isCurrentConnectionOperation(generation) else { return }
        isConnected = true
        startEventLoop()
    }

    public func disconnect() async {
        connectionOperationGeneration &+= 1
        sessionOperationGeneration &+= 1
        sessionListOperationGeneration &+= 1
        eventTask?.cancel()
        eventTask = nil
        await transport.disconnect()
        isConnected = false
        isLoadingSessions = false
    }

    public func setRuntimeSession(runtimeID: String, storedID: String) {
        sessionOperationGeneration &+= 1
        modelControlOperationGeneration &+= 1
        ChatReducer.reduce(
            &state,
            action: .sessionReady(runtimeID: runtimeID, storedID: storedID, messages: [])
        )
        resetModelControlState()
    }

    private func beginConnectionOperation() -> UInt {
        connectionOperationGeneration &+= 1
        return connectionOperationGeneration
    }

    private func isCurrentConnectionOperation(_ generation: UInt) -> Bool {
        generation == connectionOperationGeneration
    }

    private func beginSessionOperation() -> UInt {
        sessionOperationGeneration &+= 1
        return sessionOperationGeneration
    }

    private func isCurrentSessionOperation(_ generation: UInt) -> Bool {
        generation == sessionOperationGeneration
    }

    private func beginSessionListOperation() -> UInt {
        sessionListOperationGeneration &+= 1
        isLoadingSessions = true
        return sessionListOperationGeneration
    }

    private func isCurrentSessionListOperation(_ generation: UInt) -> Bool {
        generation == sessionListOperationGeneration
    }

    public func loadSessions(includeArchived: Bool = false) async throws {
        let listGeneration = beginSessionListOperation()
        let sessionGeneration = sessionOperationGeneration
        defer {
            if isCurrentSessionListOperation(listGeneration) {
                isLoadingSessions = false
            }
        }
        let parsed = try await fetchSessions(includeArchived: includeArchived)
        guard isCurrentSessionListOperation(listGeneration),
              isCurrentSessionOperation(sessionGeneration) else { return }
        applySessions(parsed)
    }

    @discardableResult
    public func restoreSession(
        storedSessionID: String?,
        profileID: String = "default"
    ) async throws -> ActiveSession {
        let sessionGeneration = beginSessionOperation()
        return try await restoreSession(
            storedSessionID: storedSessionID,
            profileID: profileID,
            sessionGeneration: sessionGeneration,
            connectionGeneration: nil
        )
    }

    @discardableResult
    public func reconnectAndRestore(
        storedSessionID: String?,
        profileID: String = "default"
    ) async throws -> ActiveSession {
        let connectionGeneration = beginConnectionOperation()
        let sessionGeneration = beginSessionOperation()
        do {
            try await transport.reconnect()
        } catch {
            if isCurrentConnectionOperation(connectionGeneration) {
                isConnected = false
            }
            throw error
        }
        try Task.checkCancellation()
        guard isCurrentConnectionOperation(connectionGeneration),
              isCurrentSessionOperation(sessionGeneration) else {
            throw CancellationError()
        }
        isConnected = true
        startEventLoop()
        return try await restoreSession(
            storedSessionID: storedSessionID,
            profileID: profileID,
            sessionGeneration: sessionGeneration,
            connectionGeneration: connectionGeneration
        )
    }

    private func restoreSession(
        storedSessionID: String?,
        profileID: String,
        sessionGeneration: UInt,
        connectionGeneration: UInt?
    ) async throws -> ActiveSession {
        let listGeneration = beginSessionListOperation()
        defer {
            if isCurrentSessionListOperation(listGeneration) {
                isLoadingSessions = false
            }
        }
        let parsed = try await fetchSessions(includeArchived: false)
        try Task.checkCancellation()
        guard isCurrentSessionListOperation(listGeneration),
              isCurrentSessionOperation(sessionGeneration),
              connectionGeneration.map(isCurrentConnectionOperation) ?? true else {
            throw CancellationError()
        }
        applySessions(parsed)

        if let storedSessionID, !storedSessionID.isEmpty {
            do {
                let active = try await requestResume(
                    storedSessionID: storedSessionID,
                    profileID: profileID,
                    generation: sessionGeneration
                )
                guard isCurrentSessionOperation(sessionGeneration) else {
                    throw CancellationError()
                }
                return active
            } catch GatewayTransportError.rpc(let error) where error.code == 4007 {
                try Task.checkCancellation()
                guard isCurrentSessionListOperation(listGeneration),
                      isCurrentSessionOperation(sessionGeneration),
                      connectionGeneration.map(isCurrentConnectionOperation) ?? true else {
                    throw CancellationError()
                }
                signalHandler?(.sessionSelectionChanged(storedID: nil))
            }
        }
        let active = try await requestCreate(profileID: profileID, generation: sessionGeneration)
        guard isCurrentSessionOperation(sessionGeneration) else {
            throw CancellationError()
        }
        return active
    }

    private func fetchSessions(includeArchived: Bool) async throws -> [SessionSummary] {
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
        return parsed
    }

    private func applySessions(_ parsed: [SessionSummary]) {
        sessions = parsed
        sortSessionsForDisplay()
    }

    @discardableResult
    public func createSession(profileID: String = "default") async throws -> ActiveSession {
        let generation = beginSessionOperation()
        return try await requestCreate(profileID: profileID, generation: generation)
    }

    private func requestCreate(profileID: String, generation: UInt) async throws -> ActiveSession {
        let result = try await transport.request(
            GatewayMethod.sessionCreate,
            params: [
                "source": .string("mobile"),
                "profile": .string(profileID),
                "cols": .number(80)
            ]
        )
        let active = try GatewayProtocol.parseActiveSession(result: result)
        guard isCurrentSessionOperation(generation) else { return active }
        applyActiveSession(active)
        insertOrUpdateSummary(
            SessionSummary(storedID: active.storedID, startedAt: Date().timeIntervalSince1970)
        )
        return active
    }

    @discardableResult
    public func resume(storedSessionID: String, profileID: String = "default") async throws -> ActiveSession {
        let generation = beginSessionOperation()
        return try await requestResume(
            storedSessionID: storedSessionID,
            profileID: profileID,
            generation: generation
        )
    }

    private func requestResume(
        storedSessionID: String,
        profileID: String,
        generation: UInt
    ) async throws -> ActiveSession {
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
        guard isCurrentSessionOperation(generation) else { return active }
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

    public func loadModelOptions(refresh: Bool = false) async throws {
        guard let runtimeID = state.runtimeSessionID else { throw ChatModelError.sessionRequired }
        let generation = beginModelControlOperation()
        isLoadingModelOptions = true
        controlErrorMessage = nil
        defer {
            if generation == modelControlOperationGeneration {
                isLoadingModelOptions = false
            }
        }
        do {
            let result = try await transport.request(
                GatewayMethod.modelOptions,
                params: [
                    "session_id": .string(runtimeID),
                    "refresh": .bool(refresh)
                ]
            )
            guard isCurrentModelControlOperation(generation, runtimeID: runtimeID) else { return }
            let catalog = GatewayProtocol.parseModelOptions(result: result)
            modelCatalog = catalog
            selectedModelID = catalog.currentModel
            selectedProviderID = catalog.currentProvider
        } catch {
            if isCurrentModelControlOperation(generation, runtimeID: runtimeID) {
                controlErrorMessage = String(localized: "error.model.options")
            }
            throw error
        }
    }

    @discardableResult
    public func selectModel(
        _ option: ModelOption,
        confirmExpensiveModel: Bool = false
    ) async throws -> ModelSwitchResult {
        guard let runtimeID = state.runtimeSessionID else { throw ChatModelError.sessionRequired }
        let generation = beginModelControlOperation()
        controlErrorMessage = nil
        var params: [String: JSONValue] = [
            "session_id": .string(runtimeID),
            "key": .string("model"),
            "value": .string("\(option.modelID) --provider \(option.providerID) --session")
        ]
        if confirmExpensiveModel { params["confirm_expensive_model"] = .bool(true) }
        do {
            let result = try await transport.request(GatewayMethod.configSet, params: params)
            guard isCurrentModelControlOperation(generation, runtimeID: runtimeID) else { return .applied }
            let outcome = GatewayProtocol.parseModelSwitch(result: result)
            switch outcome {
            case .applied:
                pendingModelConfirmation = nil
                selectedModelID = option.modelID
                selectedProviderID = option.providerID
                modelCatalog = ModelCatalog(
                    currentModel: option.modelID,
                    currentProvider: option.providerID,
                    providers: modelCatalog.providers
                )
            case .confirmationRequired(let message):
                pendingModelConfirmation = PendingModelConfirmation(
                    option: option,
                    message: message,
                    runtimeID: runtimeID,
                    operationGeneration: generation
                )
            }
            return outcome
        } catch {
            if isCurrentModelControlOperation(generation, runtimeID: runtimeID) {
                controlErrorMessage = String(localized: "error.model.switch")
            }
            throw error
        }
    }

    public func confirmPendingModelSelection() async throws {
        guard let pending = pendingModelConfirmation else { return }
        try await confirmModelSelection(pending)
    }

    public func confirmModelSelection(_ pending: PendingModelConfirmation) async throws {
        guard pending.runtimeID == state.runtimeSessionID,
              pending.operationGeneration == modelControlOperationGeneration else { return }
        _ = try await selectModel(pending.option, confirmExpensiveModel: true)
    }

    public func cancelPendingModelSelection() {
        pendingModelConfirmation = nil
    }

    public func setReasoningEffort(_ effort: String) async throws {
        guard let runtimeID = state.runtimeSessionID else { throw ChatModelError.sessionRequired }
        let generation = beginModelControlOperation()
        let normalized = effort.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        controlErrorMessage = nil
        do {
            _ = try await transport.request(
                GatewayMethod.configSet,
                params: [
                    "session_id": .string(runtimeID),
                    "key": .string("reasoning"),
                    "value": .string(normalized)
                ]
            )
            guard isCurrentModelControlOperation(generation, runtimeID: runtimeID) else { return }
            reasoningEffort = normalized
        } catch {
            if isCurrentModelControlOperation(generation, runtimeID: runtimeID) {
                controlErrorMessage = String(localized: "error.model.reasoning")
            }
            throw error
        }
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
        resetModelControlState()
        modelCatalog = ModelCatalog(
            currentModel: demoOption.modelID,
            currentProvider: demoOption.providerID,
            providers: [ModelProviderOption(id: "demo", name: "Hermes", models: [demoOption])]
        )
        selectedModelID = demoOption.modelID
        selectedProviderID = demoOption.providerID
        reasoningEffort = "medium"
    }

    private func beginModelControlOperation() -> UInt {
        modelControlOperationGeneration &+= 1
        isLoadingModelOptions = false
        return modelControlOperationGeneration
    }

    private func isCurrentModelControlOperation(_ generation: UInt, runtimeID: String) -> Bool {
        generation == modelControlOperationGeneration && state.runtimeSessionID == runtimeID
    }

    private func resetModelControlState() {
        modelControlOperationGeneration &+= 1
        modelCatalog = ModelCatalog()
        isLoadingModelOptions = false
        selectedModelID = ""
        selectedProviderID = ""
        reasoningEffort = ""
        pendingModelConfirmation = nil
        controlErrorMessage = nil
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
        resetModelControlState()
        modelCatalog = ModelCatalog(
            currentModel: active.model,
            currentProvider: active.provider
        )
        selectedModelID = active.model
        selectedProviderID = active.provider
        reasoningEffort = active.reasoningEffort
    }

    private func startEventLoop() {
        eventTask?.cancel()
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
            guard event.sessionID == nil || event.sessionID == state.runtimeSessionID else { return }
            let eventModel = payload["model"]?.stringValue.flatMap { $0.isEmpty ? nil : $0 }
            let eventProvider = payload["provider"]?.stringValue.flatMap { $0.isEmpty ? nil : $0 }
            let eventReasoningEffort = payload["reasoning_effort"]?.stringValue.flatMap { $0.isEmpty ? nil : $0 }
            let modelChanged = eventModel.map { $0 != selectedModelID } == true
                || eventProvider.map { $0 != selectedProviderID } == true
            let modelControlChanged = modelChanged
                || eventReasoningEffort.map { $0 != reasoningEffort } == true
            if modelControlChanged {
                _ = beginModelControlOperation()
                if modelChanged {
                    pendingModelConfirmation = nil
                }
            }
            if let running = payload["running"]?.boolValue {
                ChatReducer.reduce(&state, action: .streamingChanged(running))
            }
            if let model = eventModel {
                selectedModelID = model
            }
            if let provider = eventProvider {
                selectedProviderID = provider
            }
            if let effort = eventReasoningEffort {
                reasoningEffort = effort
            }
            if !selectedModelID.isEmpty || !selectedProviderID.isEmpty {
                modelCatalog = ModelCatalog(
                    currentModel: selectedModelID,
                    currentProvider: selectedProviderID,
                    providers: modelCatalog.providers
                )
            }
            if let storedID = payload["stored_session_id"]?.stringValue,
               !storedID.isEmpty,
               let runtimeID = event.sessionID,
               state.runtimeSessionID == runtimeID,
               state.storedSessionID != storedID {
                state.storedSessionID = storedID
                signalHandler?(.sessionSelectionChanged(storedID: storedID))
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