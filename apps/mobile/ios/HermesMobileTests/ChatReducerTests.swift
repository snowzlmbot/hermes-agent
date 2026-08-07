import XCTest
@testable import HermesMobile

final class ChatReducerTests: XCTestCase {
    func testStreamLifecycleAccumulatesAssistantTextReasoningAndTools() {
        var state = ChatState.empty
        ChatReducer.reduce(&state, action: .sessionReady(runtimeID: "runtime-1", storedID: "stored-1", messages: []))
        ChatReducer.reduce(&state, action: .messageStarted(sessionID: "runtime-1"))
        ChatReducer.reduce(&state, action: .reasoningDelta(sessionID: "runtime-1", text: "Planning"))
        ChatReducer.reduce(&state, action: .messageDelta(sessionID: "runtime-1", text: "Hello"))
        ChatReducer.reduce(
            &state,
            action: .toolStarted(
                sessionID: "runtime-1",
                activity: ToolActivity(id: "tool-1", name: "terminal", context: "Checking")
            )
        )
        ChatReducer.reduce(
            &state,
            action: .toolProgressed(sessionID: "runtime-1", id: "tool-1", text: "50%")
        )
        ChatReducer.reduce(
            &state,
            action: .toolCompleted(sessionID: "runtime-1", id: "tool-1", result: "ok")
        )
        ChatReducer.reduce(
            &state,
            action: .messageCompleted(sessionID: "runtime-1", text: "Hello world", status: .complete)
        )

        XCTAssertEqual(state.messages.last?.text, "Hello world")
        XCTAssertEqual(state.messages.last?.reasoning, "Planning")
        XCTAssertEqual(state.tools.last?.progress, "50%")
        XCTAssertEqual(state.tools.last?.status, .complete)
        XCTAssertFalse(state.isStreaming)
    }

    func testUnscopedStreamEventsStayPinnedUntilCompletion() {
        var state = ChatState.empty
        ChatReducer.reduce(&state, action: .sessionReady(runtimeID: "runtime-a", storedID: "stored-a", messages: []))
        ChatReducer.reduce(&state, action: .messageStarted(sessionID: nil))
        ChatReducer.reduce(&state, action: .messageDelta(sessionID: nil, text: "a"))
        ChatReducer.reduce(&state, action: .messageCompleted(sessionID: nil, text: "done", status: .complete))

        XCTAssertEqual(state.messages.last?.text, "done")
        XCTAssertFalse(state.isStreaming)
        XCTAssertNil(state.streamSessionID)
    }

    func testPromptRequestsAreStoredForApprovalClarifySecretAndSudo() {
        var state = ChatState.empty
        ChatReducer.reduce(
            &state,
            action: .approvalRequested(
                sessionID: "runtime-1",
                prompt: ApprovalPrompt(command: "run command", description: "Needs approval", choices: [.once, .deny])
            )
        )
        ChatReducer.reduce(
            &state,
            action: .clarifyRequested(
                sessionID: "runtime-1",
                prompt: ClarifyPrompt(requestID: "request-1", question: "Which file?", choices: ["A", "B"])
            )
        )
        ChatReducer.reduce(
            &state,
            action: .secretRequested(
                sessionID: "runtime-1",
                prompt: SecretPrompt(requestID: "request-2", envVar: "API_KEY", question: "Enter key")
            )
        )
        ChatReducer.reduce(
            &state,
            action: .sudoRequested(sessionID: "runtime-1", requestID: "request-3")
        )

        XCTAssertEqual(state.approval?.choices, [.once, .deny])
        XCTAssertEqual(state.clarify?.requestID, "request-1")
        XCTAssertEqual(state.secret?.envVar, "API_KEY")
        XCTAssertEqual(state.sudo?.requestID, "request-3")
    }
}
