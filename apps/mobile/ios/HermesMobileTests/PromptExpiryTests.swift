import XCTest
@testable import HermesMobile

final class PromptExpiryTests: XCTestCase {
    func testPromptExpiryClearsOnlyMatchingBlockingSurface() {
        var state = ChatState.empty
        state.runtimeSessionID = "runtime-1"
        state.clarify = ClarifyPrompt(requestID: "clarify-1", question: "Choose", choices: ["A"])
        state.secret = SecretPrompt(requestID: "secret-1", envVar: "TOKEN", question: "Token")
        state.sudo = SudoPrompt(requestID: "sudo-1")

        ChatReducer.reduce(&state, action: .clarifyResolved)
        XCTAssertNil(state.clarify)
        XCTAssertNotNil(state.secret)
        XCTAssertNotNil(state.sudo)

        ChatReducer.reduce(&state, action: .secretResolved)
        XCTAssertNil(state.secret)
        XCTAssertNotNil(state.sudo)

        ChatReducer.reduce(&state, action: .sudoResolved)
        XCTAssertNil(state.sudo)
    }
}
