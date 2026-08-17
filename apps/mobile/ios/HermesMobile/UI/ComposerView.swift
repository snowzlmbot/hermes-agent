import SwiftUI

struct ComposerView: View {
    @Binding var text: String
    let isStreaming: Bool
    let isRecording: Bool
    let isStartingRecording: Bool
    let isTranscribing: Bool
    let onSend: () -> Void
    let onStop: () -> Void
    let onAttachPhoto: () -> Void
    let onAttachFile: () -> Void
    let onRecord: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            HStack(alignment: .bottom, spacing: 8) {
                Menu {
                    Button(action: onAttachPhoto) {
                        Label(String(localized: "action.attach.photo"), systemImage: "photo")
                    }
                    Button(action: onAttachFile) {
                        Label(String(localized: "action.attach.file"), systemImage: "doc")
                    }
                } label: {
                    Image(systemName: "paperclip")
                }
                .buttonStyle(.bordered)
                .accessibilityLabel(String(localized: "action.attach"))
                .accessibilityIdentifier("Attach file")

                ZStack(alignment: .topLeading) {
                    if text.isEmpty {
                        Text(String(localized: "composer.placeholder"))
                            .foregroundStyle(.primary)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 8)
                            .fixedSize(horizontal: false, vertical: true)
                            .allowsHitTesting(false)
                            .accessibilityHidden(true)
                    }
                    TextEditor(text: $text)
                        .frame(minHeight: 44, maxHeight: 180)
                        .scrollContentBackground(.hidden)
                        .accessibilityLabel(String(localized: "composer.placeholder"))
                        .accessibilityIdentifier("Message Hermes")
                }
                .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.secondary.opacity(0.25)))
                    .textInputAutocapitalization(.sentences)

                Button(action: onRecord) {
                    Image(systemName: isRecording ? "waveform.circle.fill" : "mic")
                        .foregroundStyle(isRecording ? .red : .primary)
                }
                .buttonStyle(.bordered)
                .disabled(isStartingRecording || isTranscribing)
                .accessibilityLabel(isRecording ? String(localized: "action.stop.recording") : String(localized: "action.record"))

                if isStreaming {
                    Button(action: onStop) {
                        Image(systemName: "stop.fill")
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.red)
                    .accessibilityLabel(String(localized: "action.stop"))
                } else {
                    Button(action: onSend) {
                        Image(systemName: "arrow.up")
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityLabel(String(localized: "action.send"))
                    .accessibilityIdentifier("Send message")
                }
            }
            .frame(minHeight: 44)
        }
        .padding(.horizontal)
        .padding(.top, 8)
        .padding(.bottom, 10)
        .background(.bar)
    }
}