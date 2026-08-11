import Foundation

@MainActor
public final class EventReplayGuard {
    private struct Key: Hashable {
        let storedSessionID: String
        let eventType: String
        let stableIdentity: String
    }

    private let capacity: Int
    private var activeProfileScope: String?
    private var sequence: UInt64 = 0
    private var seen: [Key: UInt64] = [:]

    public init(capacity: Int = 1024) {
        precondition(capacity > 0, "Replay capacity must be positive")
        self.capacity = capacity
    }

    public func activate(profileScope: String) {
        let normalized = profileScope.trimmingCharacters(in: .whitespacesAndNewlines)
        precondition(!normalized.isEmpty, "Profile scope must not be empty")
        if activeProfileScope != normalized {
            seen.removeAll(keepingCapacity: true)
            sequence = 0
            activeProfileScope = normalized
        }
    }

    public func shouldConsume(
        event: GatewayEvent,
        storedSessionID: String,
        profileScope: String
    ) -> Bool {
        let normalizedProfile = profileScope.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedProfile.isEmpty,
              activeProfileScope == normalizedProfile else { return false }
        let stored = storedSessionID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !stored.isEmpty, let identity = stableIdentity(for: event) else { return true }

        sequence &+= 1
        let key = Key(
            storedSessionID: stored,
            eventType: event.type.rawValue,
            stableIdentity: identity
        )
        if seen[key] != nil {
            seen[key] = sequence
            return false
        }
        seen[key] = sequence
        evictIfNeeded()
        return true
    }

    public func clear() {
        seen.removeAll(keepingCapacity: true)
        sequence = 0
        activeProfileScope = nil
    }

    private func evictIfNeeded() {
        while seen.count > capacity {
            guard let oldest = seen.min(by: { $0.value < $1.value })?.key else { return }
            seen.removeValue(forKey: oldest)
        }
    }

    private func stableIdentity(for event: GatewayEvent) -> String? {
        if let value = primitiveIdentity(event.payload?.object?["event_id"]) {
            return "event_id:\(value)"
        }
        if let value = primitiveIdentity(event.payload?.object?["eventId"]) {
            return "event_id:\(value)"
        }
        if let value = primitiveIdentity(event.payload?.object?["sequence"]) {
            return "sequence:\(value)"
        }

        let fields: [String]
        switch event.type {
        case .messageStart, .messageComplete:
            fields = ["message_id"]
        case .toolStart, .toolComplete:
            fields = ["tool_id", "tool_call_id"]
        case .approvalRequest, .clarifyRequest, .clarifyExpire,
             .secretRequest, .secretExpire, .sudoRequest, .sudoExpire:
            fields = ["request_id", "id"]
        default:
            fields = []
        }
        for field in fields {
            if let value = primitiveIdentity(event.payload?.object?[field]) {
                return "\(field):\(value)"
            }
        }
        return nil
    }

    private func primitiveIdentity(_ value: JSONValue?) -> String? {
        guard let value else { return nil }
        switch value {
        case .string(let string):
            let normalized = string.trimmingCharacters(in: .whitespacesAndNewlines)
            return normalized.isEmpty ? nil : normalized
        case .number(let number):
            guard number.isFinite,
                  number.rounded() == number,
                  let integer = Int(exactly: number) else { return nil }
            return String(integer)
        default:
            return nil
        }
    }
}
