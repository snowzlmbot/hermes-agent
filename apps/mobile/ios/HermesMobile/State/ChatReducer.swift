import Foundation

public enum ChatReducer {
    public static func reduce(_ state: inout ChatState, action: ChatAction) {
        switch action {
        case .sessionReady(let runtimeID, let storedID, let records):
            state = .empty
            state.runtimeSessionID = runtimeID
            state.storedSessionID = storedID
            state.messages = records.enumerated().map { index, record in
                ChatMessage(
                    id: record.rowID.map { "row-\($0)" } ?? "history-\(index)",
                    role: record.role,
                    text: record.text,
                    reasoning: record.reasoning,
                    timestamp: record.timestamp,
                    status: record.error == nil ? .complete : .error
                )
            }
        case .userSubmitted(let text):
            state.messages.append(
                ChatMessage(
                    id: "local-user-\(state.messages.count)",
                    role: .user,
                    text: text,
                    timestamp: Date().timeIntervalSince1970
                )
            )
            state.errorMessage = nil
        case .messageStarted(let sessionID):
            guard accepts(sessionID: sessionID, state: state) else { return }
            state.isStreaming = true
            state.streamSessionID = sessionID ?? state.runtimeSessionID
            state.messages.append(streamingAssistant(in: state))
        case .messageDelta(let sessionID, let text):
            guard acceptsStream(sessionID: sessionID, state: state) else { return }
            ensureStreamingAssistant(&state)
            updateLastAssistant(&state) { message in
                ChatMessage(
                    id: message.id,
                    role: .assistant,
                    text: message.text + text,
                    reasoning: message.reasoning,
                    timestamp: message.timestamp,
                    status: .streaming
                )
            }
        case .messageInterim(let sessionID, let text):
            guard acceptsStream(sessionID: sessionID, state: state), !text.isEmpty else { return }
            state.messages.append(
                ChatMessage(
                    id: "interim-\(state.messages.count)",
                    role: .assistant,
                    text: text,
                    status: .complete
                )
            )
        case .reasoningDelta(let sessionID, let text):
            guard acceptsStream(sessionID: sessionID, state: state) else { return }
            ensureStreamingAssistant(&state)
            updateLastAssistant(&state) { message in
                ChatMessage(
                    id: message.id,
                    role: .assistant,
                    text: message.text,
                    reasoning: message.reasoning + text,
                    timestamp: message.timestamp,
                    status: .streaming
                )
            }
        case .messageCompleted(let sessionID, let text, let status):
            guard acceptsStream(sessionID: sessionID, state: state) else { return }
            ensureStreamingAssistant(&state)
            updateLastAssistant(&state) { message in
                ChatMessage(
                    id: message.id,
                    role: .assistant,
                    text: text.isEmpty ? message.text : text,
                    reasoning: message.reasoning,
                    timestamp: message.timestamp,
                    status: status
                )
            }
            state.isStreaming = false
            state.streamSessionID = nil
        case .toolStarted(let sessionID, let activity):
            guard acceptsStream(sessionID: sessionID, state: state) else { return }
            state.tools.removeAll { $0.id == activity.id }
            state.tools.append(activity)
        case .toolProgressed(let sessionID, let id, let text):
            guard acceptsStream(sessionID: sessionID, state: state),
                  let index = state.tools.firstIndex(where: { $0.id == id }) else { return }
            let tool = state.tools[index]
            state.tools[index] = ToolActivity(
                id: tool.id,
                name: tool.name,
                context: tool.context,
                progress: text,
                result: tool.result,
                status: .running
            )
        case .toolCompleted(let sessionID, let id, let result):
            guard acceptsStream(sessionID: sessionID, state: state),
                  let index = state.tools.firstIndex(where: { $0.id == id }) else { return }
            let tool = state.tools[index]
            state.tools[index] = ToolActivity(
                id: tool.id,
                name: tool.name,
                context: tool.context,
                progress: tool.progress,
                result: result,
                status: .complete
            )
        case .approvalRequested(_, let prompt): state.approval = prompt
        case .clarifyRequested(_, let prompt): state.clarify = prompt
        case .secretRequested(_, let prompt): state.secret = prompt
        case .sudoRequested(_, let requestID): state.sudo = SudoPrompt(requestID: requestID)
        case .approvalResolved: state.approval = nil
        case .clarifyResolved: state.clarify = nil
        case .secretResolved: state.secret = nil
        case .sudoResolved: state.sudo = nil
        case .streamingChanged(let value):
            state.isStreaming = value
            if !value { state.streamSessionID = nil }
        case .statusUpdated(let text): state.statusText = text
        case .failed(let message):
            state.errorMessage = message
            state.isStreaming = false
            state.streamSessionID = nil
        case .clearError: state.errorMessage = nil
        }
    }

    private static func accepts(sessionID: String?, state: ChatState) -> Bool {
        guard let sessionID else { return true }
        guard let runtimeID = state.runtimeSessionID else { return true }
        return sessionID == runtimeID || sessionID == state.streamSessionID
    }

    private static func acceptsStream(sessionID: String?, state: ChatState) -> Bool {
        guard let sessionID else { return state.streamSessionID != nil || state.runtimeSessionID != nil }
        return accepts(sessionID: sessionID, state: state)
    }

    private static func streamingAssistant(in state: ChatState) -> ChatMessage {
        ChatMessage(
            id: "stream-\(state.messages.count)",
            role: .assistant,
            text: "",
            status: .streaming
        )
    }

    private static func ensureStreamingAssistant(_ state: inout ChatState) {
        guard state.messages.last?.role != .assistant || state.messages.last?.status != .streaming else { return }
        state.messages.append(streamingAssistant(in: state))
        state.isStreaming = true
        if state.streamSessionID == nil { state.streamSessionID = state.runtimeSessionID }
    }

    private static func updateLastAssistant(
        _ state: inout ChatState,
        transform: (ChatMessage) -> ChatMessage
    ) {
        guard let index = state.messages.lastIndex(where: { $0.role == .assistant }) else { return }
        state.messages[index] = transform(state.messages[index])
    }
}