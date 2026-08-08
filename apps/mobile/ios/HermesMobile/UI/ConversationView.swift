import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

struct ConversationView: View {
    let navigationTitleOverride: String?

    @Environment(AppModel.self) private var appModel
    @State private var composerText = ""
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var showingPhotoPicker = false
    @State private var showingImporter = false
    @State private var showingSettings = false
    @State private var showingModelControls = false
    @State private var audioModel = AudioInteractionModel()

    private var chat: ChatModel? { appModel.chatModel }

    init(navigationTitleOverride: String? = nil) {
        self.navigationTitleOverride = navigationTitleOverride
    }

    var body: some View {
        Group {
            if let chat {
                VStack(spacing: 0) {
                    TranscriptView(
                        state: chat.state,
                        isSpeaking: audioModel.isSpeaking,
                        onSpeak: speak,
                        onStopSpeaking: { audioModel.stopSpeaking() }
                    )
                    PromptSurface(chat: chat)
                    ComposerView(
                        text: $composerText,
                        isStreaming: chat.state.isStreaming,
                        isRecording: audioModel.isRecording,
                        onSend: send,
                        onStop: stop,
                        onAttachPhoto: { showingPhotoPicker = true },
                        onAttachFile: { showingImporter = true },
                        onRecord: toggleRecording
                    )
                }
                .navigationTitle(navigationTitleOverride ?? sessionTitle(chat: chat))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button {
                            showingModelControls = true
                        } label: {
                            Label(
                                String(localized: "model.controls.title"),
                                systemImage: "slider.horizontal.3"
                            )
                            .labelStyle(.iconOnly)
                        }
                        .accessibilityIdentifier("Model controls")
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Button {
                                showingSettings = true
                            } label: {
                                Label(String(localized: "action.connection"), systemImage: "gearshape")
                            }
                            Button(role: .destructive) {
                                Task { await appModel.disconnectAndForget() }
                            } label: {
                                Label(String(localized: "action.forget.connection"), systemImage: "rectangle.portrait.and.arrow.right")
                            }
                        } label: {
                            Image(systemName: "ellipsis.circle")
                        }
                        .accessibilityLabel(String(localized: "action.more"))
                    }
                }
                .photosPicker(isPresented: $showingPhotoPicker, selection: $selectedPhoto, matching: .images)
                .onChange(of: selectedPhoto) { _, item in
                    guard let item else { return }
                    Task {
                        if let data = try? await item.loadTransferable(type: Data.self) {
                            await appModel.attachPhoto(
                                data: data,
                                contentType: item.supportedContentTypes.first
                            )
                        }
                        selectedPhoto = nil
                    }
                }
                .fileImporter(
                    isPresented: $showingImporter,
                    allowedContentTypes: [.item],
                    allowsMultipleSelection: false
                ) { result in
                    guard case .success(let urls) = result, let url = urls.first else { return }
                    Task { await appModel.attachFile(url: url) }
                }
                .sheet(isPresented: $showingModelControls) {
                    ModelControlsSheet(chat: chat)
                }
                .sheet(isPresented: $showingSettings) {
                    ConnectionSummarySheet(profile: appModel.profile)
                }
                .task {
                    if chat.state.messages.isEmpty { try? await chat.loadSessions() }
                }
            } else {
                ContentUnavailableView(
                    String(localized: "conversation.empty.title"),
                    systemImage: "bubble.left.and.bubble.right",
                    description: Text(String(localized: "conversation.empty.description"))
                )
            }
        }
    }

    private func sessionTitle(chat: ChatModel) -> String {
        guard let storedID = chat.state.storedSessionID else { return String(localized: "session.new") }
        return chat.sessions.first(where: { $0.storedID == storedID })?.displayTitle
            ?? String(localized: "session.new")
    }

    private func send() {
        let text = composerText
        composerText = ""
        Task {
            if !(await appModel.sendMessage(text)) { composerText = text }
        }
    }

    private func stop() {
        Task { await appModel.stopMessage() }
    }

    private func toggleRecording() {
        if audioModel.isRecording {
            guard let client = appModel.gatewayRESTClient else { return }
            Task {
                if let text = await audioModel.stopAndTranscribe(using: client) {
                    composerText = composerText.isEmpty ? text : "\(composerText) \(text)"
                }
            }
        } else {
            Task { await audioModel.startRecording() }
        }
    }

    private func speak(_ text: String) {
        guard let client = appModel.gatewayRESTClient else { return }
        Task { await audioModel.speak(text, using: client) }
    }
}

private struct ConnectionSummarySheet: View {
    let profile: GatewayProfile?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                LabeledContent(String(localized: "connection.address"), value: profile?.endpoint ?? "")
                LabeledContent(String(localized: "connection.mode"), value: profile?.authMode.displayName ?? "")
                Text(String(localized: "connection.secure.storage"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .navigationTitle(String(localized: "action.connection"))
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "action.done")) { dismiss() }
                }
            }
        }
    }
}