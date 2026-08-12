import SwiftUI

struct SessionListView: View {
    @Environment(AppModel.self) private var appModel
    @State private var renameTarget: SessionSummary?
    @State private var renameText = ""
    @State private var deleteTarget: SessionSummary?
    @State private var libraryView: SessionLibraryView = .active
    @State private var searchQuery = ""

    private var sessions: [SessionSummary] {
        appModel.chatModel?.sessions ?? []
    }

    private var visibleSessions: [SessionSummary] {
        SessionLibrary.filter(sessions, view: libraryView, query: searchQuery)
    }

    var body: some View {
        List(selection: Binding(
            get: { appModel.selectedSessionID },
            set: { next in
                guard let next,
                      sessions.first(where: { $0.storedID == next })?.archived != true else { return }
                Task { await appModel.selectSession(next) }
            }
        )) {
            if visibleSessions.isEmpty {
                ContentUnavailableView(
                    searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        ? String(localized: "sessions.empty.title")
                        : String(localized: "sessions.search.empty.title"),
                    systemImage: "bubble.left.and.bubble.right",
                    description: Text(
                        searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            ? String(localized: "sessions.empty.description")
                            : String(localized: "sessions.search.empty.description")
                    )
                )
            } else {
                ForEach(visibleSessions) { session in
                    SessionRow(session: session)
                        .tag(session.storedID)
                        .contextMenu {
                            if session.archived {
                                Button {
                                    Task { await appModel.restoreSession(session.storedID) }
                                } label: {
                                    Label(String(localized: "action.restore"), systemImage: "archivebox.fill")
                                }
                            } else {
                                Button {
                                    Task { await appModel.setSessionPinned(!session.pinned, storedID: session.storedID) }
                                } label: {
                                    Label(
                                        String(localized: session.pinned ? "action.unpin" : "action.pin"),
                                        systemImage: session.pinned ? "pin.slash" : "pin"
                                    )
                                }
                                Button {
                                    renameTarget = session
                                    renameText = session.displayTitle
                                } label: {
                                    Label(String(localized: "action.rename"), systemImage: "pencil")
                                }
                                Button {
                                    Task { await appModel.archiveSession(session.storedID) }
                                } label: {
                                    Label(String(localized: "action.archive"), systemImage: "archivebox")
                                }
                            }
                            Button(role: .destructive) {
                                deleteTarget = session
                            } label: {
                                Label(String(localized: "action.delete"), systemImage: "trash")
                            }
                        }
                        .swipeActions(edge: .leading, allowsFullSwipe: true) {
                            if session.archived {
                                Button {
                                    Task { await appModel.restoreSession(session.storedID) }
                                } label: {
                                    Label(String(localized: "action.restore"), systemImage: "archivebox.fill")
                                }
                                .tint(.green)
                            } else {
                                Button {
                                    Task { await appModel.archiveSession(session.storedID) }
                                } label: {
                                    Label(String(localized: "action.archive"), systemImage: "archivebox")
                                }
                                .tint(.orange)
                            }
                        }
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button(role: .destructive) { deleteTarget = session } label: {
                                Label(String(localized: "action.delete"), systemImage: "trash")
                            }
                        }
                }
            }
        }
        .listStyle(.sidebar)
        .searchable(text: $searchQuery, prompt: String(localized: "sessions.search.prompt"))
        .safeAreaInset(edge: .top, spacing: 0) {
            Picker(String(localized: "sessions.view"), selection: $libraryView) {
                Text(String(localized: "sessions.active")).tag(SessionLibraryView.active)
                Text(String(localized: "sessions.archived")).tag(SessionLibraryView.archived)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.vertical, 8)
        }
        .refreshable {
            guard appModel.chatModel?.isConnected == true else { return }
            try? await appModel.chatModel?.loadSessions(includeArchived: true)
        }
        .task {
            guard appModel.chatModel?.isConnected == true else { return }
            try? await appModel.chatModel?.loadSessions(includeArchived: true)
        }
        .sheet(item: $renameTarget) { target in
            RenameSessionSheet(
                title: String(localized: "action.rename"),
                text: $renameText,
                onCancel: { renameTarget = nil },
                onSave: {
                    Task {
                        await appModel.renameSession(target.storedID, title: renameText)
                        renameTarget = nil
                    }
                }
            )
            .presentationDetents([.height(220)])
        }
        .confirmationDialog(
            String(localized: "delete.session.title"),
            isPresented: Binding(
                get: { deleteTarget != nil },
                set: { if !$0 { deleteTarget = nil } }
            ),
            presenting: deleteTarget
        ) { target in
            Button(String(localized: "action.delete"), role: .destructive) {
                Task { await appModel.deleteSession(target.storedID) }
                deleteTarget = nil
            }
            Button(String(localized: "action.cancel"), role: .cancel) { deleteTarget = nil }
        } message: { _ in
            Text(String(localized: "delete.session.message"))
        }
    }
}

private struct SessionRow: View {
    let session: SessionSummary

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Text(session.displayTitle)
                    .font(.headline)
                    .lineLimit(1)
                if session.pinned {
                    Image(systemName: "pin.fill")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Text(session.preview.isEmpty ? String(localized: "session.no.preview") : session.preview)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(session.displayTitle)
        .accessibilityValue(session.preview)
    }
}

private struct RenameSessionSheet: View {
    let title: String
    @Binding var text: String
    let onCancel: () -> Void
    let onSave: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                TextField(String(localized: "session.title.field"), text: $text)
            }
            .navigationTitle(title)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "action.cancel"), action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "action.save"), action: onSave)
                        .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}