import Foundation

public enum GatewayProtocolError: Error, Equatable, Sendable {
    case invalidResult
    case missingRuntimeSessionID
    case missingStoredSessionID
}

public enum ModelSwitchResult: Equatable, Sendable {
    case applied
    case confirmationRequired(message: String)
}

public struct PendingModelConfirmation: Equatable, Sendable {
    public let option: ModelOption
    public let message: String
    public let runtimeID: String

    public init(option: ModelOption, message: String, runtimeID: String) {
        self.option = option
        self.message = message
        self.runtimeID = runtimeID
    }
}

public enum MessageRole: String, Codable, Equatable, Hashable, Sendable {
    case user
    case assistant
    case tool
    case system
    case unknown

    public init(wireValue: String?) {
        switch wireValue?.lowercased() {
        case "user": self = .user
        case "assistant": self = .assistant
        case "tool": self = .tool
        case "system": self = .system
        default: self = .unknown
        }
    }
}

public struct SessionSummary: Identifiable, Codable, Equatable, Hashable, Sendable {
    public let storedID: String
    public var title: String
    public var preview: String
    public var startedAt: Double
    public var lastActive: Double
    public var messageCount: Int
    public var source: String
    public var archived: Bool
    public var pinned: Bool

    public var id: String { storedID }
    public var displayTitle: String {
        let candidate = title.trimmingCharacters(in: .whitespacesAndNewlines)
        if !candidate.isEmpty { return candidate }
        let fallback = preview.trimmingCharacters(in: .whitespacesAndNewlines)
        return fallback.isEmpty ? String(localized: "session.new") : fallback
    }

    public init(
        storedID: String,
        title: String = "",
        preview: String = "",
        startedAt: Double = 0,
        lastActive: Double = 0,
        messageCount: Int = 0,
        source: String = "",
        archived: Bool = false,
        pinned: Bool = false
    ) {
        self.storedID = storedID
        self.title = title
        self.preview = preview
        self.startedAt = startedAt
        self.lastActive = lastActive
        self.messageCount = messageCount
        self.source = source
        self.archived = archived
        self.pinned = pinned
    }
}

public struct ModelOption: Identifiable, Equatable, Hashable, Sendable {
    public let providerID: String
    public let providerName: String
    public let modelID: String
    public let supportsFast: Bool
    public let supportsReasoning: Bool

    public var id: String { modelID }

    public init(
        providerID: String,
        providerName: String,
        modelID: String,
        supportsFast: Bool = false,
        supportsReasoning: Bool = false
    ) {
        self.providerID = providerID
        self.providerName = providerName
        self.modelID = modelID
        self.supportsFast = supportsFast
        self.supportsReasoning = supportsReasoning
    }
}

public struct ModelProviderOption: Identifiable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let models: [ModelOption]
    public let apiURL: String?

    public init(id: String, name: String, models: [ModelOption], apiURL: String? = nil) {
        self.id = id
        self.name = name
        self.models = models
        self.apiURL = apiURL
    }
}

public struct ModelCatalog: Equatable, Sendable {
    public let currentModel: String
    public let currentProvider: String
    public let providers: [ModelProviderOption]

    public init(
        currentModel: String = "",
        currentProvider: String = "",
        providers: [ModelProviderOption] = []
    ) {
        self.currentModel = currentModel
        self.currentProvider = currentProvider
        self.providers = providers
    }
}

public struct ChatMessageRecord: Identifiable, Codable, Equatable, Sendable {
    public let rowID: Int64?
    public let role: MessageRole
    public let text: String
    public let timestamp: Double?
    public let pending: Bool
    public let error: String?
    public let reasoning: String

    public var id: String {
        if let rowID { return "row-\(rowID)" }
        return "message-\(text.hashValue)"
    }

    public init(
        rowID: Int64? = nil,
        role: MessageRole,
        text: String,
        timestamp: Double? = nil,
        pending: Bool = false,
        error: String? = nil,
        reasoning: String = ""
    ) {
        self.rowID = rowID
        self.role = role
        self.text = text
        self.timestamp = timestamp
        self.pending = pending
        self.error = error
        self.reasoning = reasoning
    }
}

public struct ActiveSession: Equatable, Sendable {
    public let runtimeID: String
    public let storedID: String
    public let messages: [ChatMessageRecord]
    public let running: Bool
    public let status: String
    public let model: String
    public let provider: String
    public let reasoningEffort: String

    public init(
        runtimeID: String,
        storedID: String,
        messages: [ChatMessageRecord] = [],
        running: Bool = false,
        status: String = "idle",
        model: String = "",
        provider: String = "",
        reasoningEffort: String = ""
    ) {
        self.runtimeID = runtimeID
        self.storedID = storedID
        self.messages = messages
        self.running = running
        self.status = status
        self.model = model
        self.provider = provider
        self.reasoningEffort = reasoningEffort
    }
}

public enum GatewayProtocol {
    public static func parseSessionList(result: JSONValue) -> [SessionSummary] {
        let values = result.object?["sessions"]?.array
            ?? result.object?["data"]?.array
            ?? []
        return values.compactMap { value in
            guard let object = value.object,
                  let storedID = object["id"]?.stringValue,
                  !storedID.isEmpty else { return nil }
            return SessionSummary(
                storedID: storedID,
                title: object["title"]?.stringValue ?? "",
                preview: object["preview"]?.stringValue ?? "",
                startedAt: object["started_at"]?.numberValue ?? 0,
                lastActive: object["last_active"]?.numberValue ?? object["started_at"]?.numberValue ?? 0,
                messageCount: Int(object["message_count"]?.numberValue ?? 0),
                source: object["source"]?.stringValue ?? "",
                archived: object["archived"]?.boolValue ?? false,
                pinned: object["pinned"]?.boolValue ?? false
            )
        }
    }

    public static func parseActiveSession(result: JSONValue) throws -> ActiveSession {
        guard let object = result.object,
              let runtimeID = object["session_id"]?.stringValue,
              !runtimeID.isEmpty else { throw GatewayProtocolError.missingRuntimeSessionID }
        let info = object["info"]?.object ?? [:]
        let storedID = object["resumed"]?.stringValue
            ?? object["stored_session_id"]?.stringValue
            ?? info["stored_session_id"]?.stringValue
        guard let storedID, !storedID.isEmpty else { throw GatewayProtocolError.missingStoredSessionID }
        let running = object["running"]?.boolValue ?? info["running"]?.boolValue ?? false
        return ActiveSession(
            runtimeID: runtimeID,
            storedID: storedID,
            messages: parseMessages(object["messages"]),
            running: running,
            status: object["status"]?.stringValue ?? (running ? "streaming" : "idle"),
            model: info["model"]?.stringValue ?? "",
            provider: info["provider"]?.stringValue ?? "",
            reasoningEffort: info["reasoning_effort"]?.stringValue ?? ""
        )
    }

    public static func parseModelSwitch(result: JSONValue) -> ModelSwitchResult {
        let root = result.object ?? [:]
        if root["confirm_required"]?.boolValue == true {
            let message = root["confirm_message"]?.stringValue
                ?.trimmingCharacters(in: .whitespacesAndNewlines)
            return .confirmationRequired(message: message?.isEmpty == false ? message! : String(localized: "model.confirm.default"))
        }
        return .applied
    }

    public static func parseModelOptions(result: JSONValue) -> ModelCatalog {
        let root = result.object ?? [:]
        let providers = (root["providers"]?.array ?? []).compactMap { value -> ModelProviderOption? in
            guard let object = value.object,
                  object["authenticated"]?.boolValue == true,
                  let providerID = object["slug"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !providerID.isEmpty else { return nil }
            let candidateName = object["name"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let providerName = candidateName.isEmpty ? providerID : candidateName
            let capabilities = object["capabilities"]?.object ?? [:]
            var seen = Set<String>()
            let models = (object["models"]?.array ?? []).compactMap { item -> ModelOption? in
                guard let modelID = item.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines),
                      !modelID.isEmpty,
                      seen.insert(modelID).inserted else { return nil }
                let modelCapabilities = capabilities[modelID]?.object ?? [:]
                return ModelOption(
                    providerID: providerID,
                    providerName: providerName,
                    modelID: modelID,
                    supportsFast: modelCapabilities["fast"]?.boolValue ?? false,
                    supportsReasoning: modelCapabilities["reasoning"]?.boolValue ?? false
                )
            }
            return ModelProviderOption(
                id: providerID,
                name: providerName,
                models: models,
                apiURL: object["api_url"]?.stringValue
            )
        }
        return ModelCatalog(
            currentModel: root["model"]?.stringValue ?? "",
            currentProvider: root["provider"]?.stringValue ?? "",
            providers: providers
        )
    }

    public static func parseMessages(_ value: JSONValue?) -> [ChatMessageRecord] {
        (value?.array ?? []).compactMap { item in
            guard let object = item.object else { return nil }
            let text = object["content"]?.stringValue ?? object["text"]?.stringValue ?? ""
            return ChatMessageRecord(
                rowID: object["id"]?.numberValue.map { Int64($0) },
                role: MessageRole(wireValue: object["role"]?.stringValue),
                text: text,
                timestamp: object["timestamp"]?.numberValue,
                pending: object["pending"]?.boolValue ?? false,
                error: object["error"]?.stringValue,
                reasoning: object["reasoning"]?.stringValue ?? object["reasoning_content"]?.stringValue ?? ""
            )
        }
    }
}