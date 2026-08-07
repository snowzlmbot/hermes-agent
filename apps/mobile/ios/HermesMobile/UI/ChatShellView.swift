import SwiftUI

struct ChatShellView: View {
    @Environment(AppModel.self) private var appModel
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var columnVisibility: NavigationSplitViewVisibility = .all
    @State private var showingSessions = false

    var body: some View {
        if horizontalSizeClass == .compact {
            NavigationStack {
                ConversationView(navigationTitleOverride: String(localized: "sessions.title"))
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button { showingSessions = true } label: {
                            Image(systemName: "list.bullet")
                        }
                        .accessibilityLabel(String(localized: "sessions.title"))
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            Task { await appModel.createSession() }
                        } label: {
                            Image(systemName: "plus")
                        }
                        .accessibilityLabel(String(localized: "action.new.session"))
                    }
                }
            }
            .sheet(isPresented: $showingSessions) {
                NavigationStack {
                    SessionListView()
                        .navigationTitle(String(localized: "sessions.title"))
                        .toolbar {
                            ToolbarItem(placement: .confirmationAction) {
                                Button(String(localized: "action.done")) { showingSessions = false }
                            }
                        }
                }
            }
        } else {
            NavigationSplitView(columnVisibility: $columnVisibility) {
                SessionListView()
                    .navigationTitle(String(localized: "sessions.title"))
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button {
                                Task { await appModel.createSession() }
                            } label: {
                                Image(systemName: "plus")
                            }
                            .accessibilityLabel(String(localized: "action.new.session"))
                        }
                    }
            } detail: {
                ConversationView()
            }
            .navigationSplitViewStyle(.balanced)
        }
    }
}