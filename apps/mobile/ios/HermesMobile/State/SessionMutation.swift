import Foundation

public struct SessionMutation: Equatable, Sendable {
    public let storedID: String
    public let title: String?
    public let archived: Bool?
    public let pinned: Bool?

    public init(storedID: String, title: String? = nil, archived: Bool? = nil, pinned: Bool? = nil) {
        self.storedID = storedID
        self.title = title
        self.archived = archived
        self.pinned = pinned
    }
}

public protocol SessionMutationClient: Sendable {
    func patchSession(_ mutation: SessionMutation) async throws
    func deleteSession(_ storedID: String) async throws
}

extension GatewayRESTClient: SessionMutationClient {}

public protocol SessionArchiveStore: Sendable {
    func setArchived(_ archived: Bool, storedID: String) async throws
    func isArchived(_ storedID: String) async -> Bool
}

public actor InMemorySessionArchiveStore: SessionArchiveStore {
    private var identifiers: Set<String> = []

    public init() {}

    public func setArchived(_ archived: Bool, storedID: String) async throws {
        if archived {
            identifiers.insert(storedID)
        } else {
            identifiers.remove(storedID)
        }
    }

    public func isArchived(_ storedID: String) async -> Bool {
        identifiers.contains(storedID)
    }
}