import XCTest
@testable import HermesMobile

final class SessionLibraryTests: XCTestCase {
    func testSearchesContractFieldsWithoutReordering() {
        let sessions = fixtures()
        XCTAssertEqual(SessionLibrary.filter(sessions, view: .active, query: " ROADMAP ").map(\.storedID), ["pinned"])
        XCTAssertEqual(SessionLibrary.filter(sessions, view: .active, query: "KOTLIN").map(\.storedID), ["active"])
        XCTAssertEqual(SessionLibrary.filter(sessions, view: .archived, query: "stored-ARCHIVE").map(\.storedID), ["stored-archive"])
    }

    func testEmptyQueryPreservesCurrentViewOrder() {
        let sessions = fixtures()
        XCTAssertEqual(SessionLibrary.filter(sessions, view: .active, query: "  ").map(\.storedID), ["pinned", "active"])
        XCTAssertEqual(SessionLibrary.filter(sessions, view: .archived, query: "").map(\.storedID), ["stored-archive"])
    }

    private func fixtures() -> [SessionSummary] {
        [
            summary("pinned", "Pinned Roadmap", "Release Preview", archived: false, pinned: true),
            summary("active", "Active Notes", "Kotlin details", archived: false),
            summary("stored-archive", "Old Research", "Swift Preview", archived: true)
        ]
    }

    private func summary(_ id: String, _ title: String, _ preview: String, archived: Bool, pinned: Bool = false) -> SessionSummary {
        SessionSummary(storedID: id, title: title, preview: preview, startedAt: 1, lastActive: 1, messageCount: 1, source: "mobile", archived: archived, pinned: pinned)
    }
}
