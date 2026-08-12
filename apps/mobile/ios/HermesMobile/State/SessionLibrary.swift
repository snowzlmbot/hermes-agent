import Foundation

enum SessionLibraryView: String, CaseIterable, Identifiable, Sendable {
    case active
    case archived
    var id: String { rawValue }
}

enum SessionLibrary {
    static func filter(_ sessions: [SessionSummary], view: SessionLibraryView, query: String) -> [SessionSummary] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return sessions.filter { session in
            let belongs = session.archived == (view == .archived)
            return belongs && (needle.isEmpty || matches(session, needle))
        }
    }

    private static func matches(_ session: SessionSummary, _ query: String) -> Bool {
        let fields = [session.displayTitle, session.title, session.preview, session.storedID]
        return fields.contains { $0.range(of: query, options: [.caseInsensitive, .diacriticInsensitive]) != nil }
    }
}
