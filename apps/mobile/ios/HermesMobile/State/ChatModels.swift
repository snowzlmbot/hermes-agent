import Foundation

public enum MessageCompletionStatus: String, Codable, Equatable, Sendable {
    case streaming
    case complete
    case interrupted
    case error
}

public struct ChatMessage: Identifiable, Codable, Equatable, Sendable {
    public let id: String
    public let role: MessageRole
    public let text: String
    public let reasoning: String
    public let timestamp: Double?
    public let status: MessageCompletionStatus

    public init(
        id: String,
        role: MessageRole,
        text: String,
        reasoning: String = "",
        timestamp: Double? = nil,
        status: MessageCompletionStatus = .complete
    ) {
        self.id = id
        self.role = role
        self.text = text
        self.reasoning = reasoning
        self.timestamp = timestamp
        self.status = status
    }
}

public enum ToolActivityStatus: String, Codable, Equatable, Sendable {
    case running
    case complete
    case failed
}

public struct ToolActivity: Identifiable, Codable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let context: String
    public let progress: String
    public let result: String
    public let status: ToolActivityStatus

    public init(
        id: String,
        name: String,
        context: String = "",
        progress: String = "",
        result: String = "",
        status: ToolActivityStatus = .running
    ) {
        self.id = id
        self.name = name
        self.context = context
        self.progress = progress
        self.result = result
        self.status = status
    }
}

public enum ApprovalChoice: String, Codable, CaseIterable, Identifiable, Equatable, Sendable {
    case once
    case session
    case always
    case deny

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .once: return String(localized: "approval.choice.once")
        case .session: return String(localized: "approval.choice.session")
        case .always: return String(localized: "approval.choice.always")
        case .deny: return String(localized: "approval.choice.deny")
        }
    }
}

public struct ApprovalPrompt: Codable, Equatable, Sendable {
    public let command: String
    public let description: String
    public let choices: [ApprovalChoice]

    public init(command: String, description: String, choices: [ApprovalChoice]) {
        self.command = command
        self.description = description
        self.choices = choices
    }
}

public struct ClarifyPrompt: Codable, Equatable, Sendable {
    public let requestID: String
    public let question: String
    public let choices: [String]
    public let allowsMultipleSelection: Bool

    public init(
        requestID: String,
        question: String,
        choices: [String],
        allowsMultipleSelection: Bool = false
    ) {
        self.requestID = requestID
        self.question = question
        self.choices = choices
        self.allowsMultipleSelection = allowsMultipleSelection
    }
}

public struct SecretPrompt: Codable, Equatable, Sendable {
    public let requestID: String
    public let envVar: String
    public let question: String

    public init(requestID: String, envVar: String, question: String) {
        self.requestID = requestID
        self.envVar = envVar
        self.question = question
    }
}

public struct SudoPrompt: Codable, Equatable, Sendable {
    public let requestID: String

    public init(requestID: String) {
        self.requestID = requestID
    }
}

public struct ChatState: Equatable, Sendable {
    public var runtimeSessionID: String?
    public var storedSessionID: String?
    public var messages: [ChatMessage]
    public var tools: [ToolActivity]
    public var isStreaming: Bool
    public var streamSessionID: String?
    public var approval: ApprovalPrompt?
    public var clarify: ClarifyPrompt?
    public var secret: SecretPrompt?
    public var sudo: SudoPrompt?
    public var statusText: String
    public var errorMessage: String?

    public static let empty = ChatState(
        runtimeSessionID: nil,
        storedSessionID: nil,
        messages: [],
        tools: [],
        isStreaming: false,
        streamSessionID: nil,
        approval: nil,
        clarify: nil,
        secret: nil,
        sudo: nil,
        statusText: "",
        errorMessage: nil
    )
}

public enum ChatAction: Equatable, Sendable {
    case sessionReady(runtimeID: String, storedID: String, messages: [ChatMessageRecord])
    case userSubmitted(text: String)
    case messageStarted(sessionID: String?)
    case messageDelta(sessionID: String?, text: String)
    case messageInterim(sessionID: String?, text: String)
    case reasoningDelta(sessionID: String?, text: String)
    case messageCompleted(sessionID: String?, text: String, status: MessageCompletionStatus)
    case toolStarted(sessionID: String?, activity: ToolActivity)
    case toolProgressed(sessionID: String?, id: String, text: String)
    case toolCompleted(sessionID: String?, id: String, result: String)
    case approvalRequested(sessionID: String?, prompt: ApprovalPrompt)
    case clarifyRequested(sessionID: String?, prompt: ClarifyPrompt)
    case secretRequested(sessionID: String?, prompt: SecretPrompt)
    case sudoRequested(sessionID: String?, requestID: String)
    case approvalResolved
    case clarifyResolved
    case secretResolved
    case sudoResolved
    case streamingChanged(Bool)
    case statusUpdated(String)
    case failed(String)
    case clearError
}

public enum ChatSignal: Equatable, Sendable {
    case messageCompleted(sessionID: String?)
    case approvalRequired(sessionID: String?)
    case inputRequired(sessionID: String?)
    case sessionsChanged
    case sessionSelectionChanged(storedID: String)
}