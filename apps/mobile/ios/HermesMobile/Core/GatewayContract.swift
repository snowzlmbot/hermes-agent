import Foundation

public enum GatewayMethod {
    public static let sessionList = "session.list"
    public static let sessionCreate = "session.create"
    public static let sessionResume = "session.resume"
    public static let sessionTitle = "session.title"
    public static let sessionDelete = "session.delete"
    public static let sessionInterrupt = "session.interrupt"
    public static let promptSubmit = "prompt.submit"
    public static let modelOptions = "model.options"
    public static let configSet = "config.set"
    public static let approvalRespond = "approval.respond"
    public static let clarifyRespond = "clarify.respond"
    public static let secretRespond = "secret.respond"
    public static let sudoRespond = "sudo.respond"
    public static let imageAttachBytes = "image.attach_bytes"
    public static let pdfAttach = "pdf.attach"
    public static let fileAttach = "file.attach"

    public static let all: [String] = [
        sessionList, sessionCreate, sessionResume, sessionTitle, sessionDelete,
        sessionInterrupt, promptSubmit, modelOptions, configSet, approvalRespond,
        clarifyRespond, secretRespond, sudoRespond, imageAttachBytes, pdfAttach,
        fileAttach
    ]
}

public enum GatewayEventType: Equatable, Hashable, Sendable, Codable {
    case gatewayReady
    case sessionInfo
    case messageStart
    case messageDelta
    case messageInterim
    case messageComplete
    case reasoningDelta
    case thinkingDelta
    case toolStart
    case toolProgress
    case toolComplete
    case approvalRequest
    case clarifyRequest
    case clarifyExpire
    case secretRequest
    case secretExpire
    case sudoRequest
    case sudoExpire
    case error
    case sessionsChanged
    case sessionTitle
    case statusUpdate
    case unknown(String)

    public static let all: [GatewayEventType] = [
        .gatewayReady, .sessionInfo, .messageStart, .messageDelta,
        .messageInterim, .messageComplete, .reasoningDelta, .thinkingDelta,
        .toolStart, .toolProgress, .toolComplete, .approvalRequest,
        .clarifyRequest, .clarifyExpire, .secretRequest, .secretExpire,
        .sudoRequest, .sudoExpire, .error, .sessionsChanged,
        .sessionTitle, .statusUpdate
    ]

    public init(rawValue: String) {
        switch rawValue {
        case "gateway.ready": self = .gatewayReady
        case "session.info": self = .sessionInfo
        case "message.start": self = .messageStart
        case "message.delta": self = .messageDelta
        case "message.interim": self = .messageInterim
        case "message.complete": self = .messageComplete
        case "reasoning.delta": self = .reasoningDelta
        case "thinking.delta": self = .thinkingDelta
        case "tool.start": self = .toolStart
        case "tool.progress": self = .toolProgress
        case "tool.complete": self = .toolComplete
        case "approval.request": self = .approvalRequest
        case "clarify.request": self = .clarifyRequest
        case "clarify.expire": self = .clarifyExpire
        case "secret.request": self = .secretRequest
        case "secret.expire": self = .secretExpire
        case "sudo.request": self = .sudoRequest
        case "sudo.expire": self = .sudoExpire
        case "error": self = .error
        case "sessions.changed": self = .sessionsChanged
        case "session.title": self = .sessionTitle
        case "status.update": self = .statusUpdate
        default: self = .unknown(rawValue)
        }
    }

    public var rawValue: String {
        switch self {
        case .gatewayReady: return "gateway.ready"
        case .sessionInfo: return "session.info"
        case .messageStart: return "message.start"
        case .messageDelta: return "message.delta"
        case .messageInterim: return "message.interim"
        case .messageComplete: return "message.complete"
        case .reasoningDelta: return "reasoning.delta"
        case .thinkingDelta: return "thinking.delta"
        case .toolStart: return "tool.start"
        case .toolProgress: return "tool.progress"
        case .toolComplete: return "tool.complete"
        case .approvalRequest: return "approval.request"
        case .clarifyRequest: return "clarify.request"
        case .clarifyExpire: return "clarify.expire"
        case .secretRequest: return "secret.request"
        case .secretExpire: return "secret.expire"
        case .sudoRequest: return "sudo.request"
        case .sudoExpire: return "sudo.expire"
        case .error: return "error"
        case .sessionsChanged: return "sessions.changed"
        case .sessionTitle: return "session.title"
        case .statusUpdate: return "status.update"
        case .unknown(let value): return value
        }
    }

    public init(from decoder: Decoder) throws {
        self.init(rawValue: try String(from: decoder))
    }

    public func encode(to encoder: Encoder) throws {
        try rawValue.encode(to: encoder)
    }
}

public struct JSONRPCID: Codable, Equatable, Hashable, Sendable {
    public enum Value: Codable, Equatable, Hashable, Sendable {
        case string(String)
        case number(Int)
    }

    public let value: Value

    public static func string(_ value: String) -> Self { Self(value: .string(value)) }
    public static func number(_ value: Int) -> Self { Self(value: .number(value)) }

    public init(value: Value) { self.value = value }

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let value = try? container.decode(String.self) {
            self.value = .string(value)
        } else {
            self.value = .number(try container.decode(Int.self))
        }
    }

    public func encode(to encoder: Encoder) throws {
        switch value {
        case .string(let value): try value.encode(to: encoder)
        case .number(let value): try value.encode(to: encoder)
        }
    }
}

public struct JSONRPCError: Codable, Equatable, Sendable {
    public let code: Int
    public let message: String
    public let data: JSONValue?

    public init(code: Int, message: String, data: JSONValue? = nil) {
        self.code = code
        self.message = message
        self.data = data
    }
}

public struct JSONRPCRequest: Codable, Equatable, Sendable {
    public let jsonrpc: String
    public let id: JSONRPCID
    public let method: String
    public let params: [String: JSONValue]?

    public init(id: JSONRPCID, method: String, params: [String: JSONValue]? = nil) {
        self.jsonrpc = "2.0"
        self.id = id
        self.method = method
        self.params = params
    }
}

public struct JSONRPCResponse: Codable, Equatable, Sendable {
    public let jsonrpc: String
    public let id: JSONRPCID
    public let result: JSONValue?
    public let error: JSONRPCError?

    public init(id: JSONRPCID, result: JSONValue? = nil, error: JSONRPCError? = nil) {
        self.jsonrpc = "2.0"
        self.id = id
        self.result = result
        self.error = error
    }
}

public struct GatewayEvent: Codable, Equatable, Sendable {
    public let type: GatewayEventType
    public let sessionID: String?
    public let payload: JSONValue?

    public init(type: GatewayEventType, sessionID: String?, payload: JSONValue?) {
        self.type = type
        self.sessionID = sessionID
        self.payload = payload
    }

    enum CodingKeys: String, CodingKey { case type, sessionID = "session_id", payload }
}

public struct JSONRPCEventFrame: Codable, Equatable, Sendable {
    public let jsonrpc: String
    public let method: String
    public let params: GatewayEvent

    public init(event: GatewayEvent) {
        self.jsonrpc = "2.0"
        self.method = "event"
        self.params = event
    }
}

public enum JSONRPCInboundFrame: Decodable, Equatable, Sendable {
    case response(JSONRPCResponse)
    case event(GatewayEvent)

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: DynamicCodingKey.self)
        if let method = try container.decodeIfPresent(String.self, forKey: DynamicCodingKey("method")), method == "event" {
            let frame = try JSONRPCEventFrame(from: decoder)
            self = .event(frame.params)
        } else {
            self = .response(try JSONRPCResponse(from: decoder))
        }
    }
}

private struct DynamicCodingKey: CodingKey {
    let stringValue: String
    let intValue: Int? = nil
    init(_ string: String) { stringValue = string }
    init?(stringValue: String) { self.init(stringValue) }
    init?(intValue: Int) { return nil }
}

public struct MobileContract: Decodable, Sendable {
    public let schemaVersion: Int
    public let rpcMethods: [String]
    public let eventTypes: [GatewayEventType]
    public let frames: Frames
    public let notificationRouting: NotificationRouting

    public struct NotificationRouting: Decodable, Sendable {
        public let identityField: String
        public let allowedFields: [String]
        public let profileScopeField: String
        public let profileScopeEncoding: String
        public let profileScopeInput: String
        public let scopeMismatchAction: String
        public let singleConsume: Bool
        public let forbiddenFields: [String]

        enum CodingKeys: String, CodingKey {
            case identityField = "identity_field"
            case allowedFields = "allowed_fields"
            case profileScopeField = "profile_scope_field"
            case profileScopeEncoding = "profile_scope_encoding"
            case profileScopeInput = "profile_scope_input"
            case scopeMismatchAction = "scope_mismatch_action"
            case singleConsume = "single_consume"
            case forbiddenFields = "forbidden_fields"
        }
    }

    public struct Frames: Decodable, Sendable {
        public let request: [String: JSONValue]
        public let event: [String: JSONValue]
    }

    public enum ContractError: Error { case missingBundleResource }

    public var requestExample: [String: JSONValue] { frames.request }
    public var eventExample: [String: JSONValue] { frames.event }

    enum CodingKeys: String, CodingKey {
        case schemaVersion = "schema_version"
        case rpcMethods = "rpc_methods"
        case eventTypes = "event_types"
        case frames
        case notificationRouting = "notification_routing"
    }

    public static func loadBundled(bundle: Bundle = .main) throws -> MobileContract {
        guard let url = bundle.url(forResource: "contract", withExtension: "json") else {
            throw ContractError.missingBundleResource
        }
        return try JSONDecoder().decode(Self.self, from: Data(contentsOf: url))
    }
}
