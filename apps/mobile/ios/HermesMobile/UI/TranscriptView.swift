import SwiftUI
import UIKit

struct TranscriptView: View {
    let state: ChatState
    let isSpeaking: Bool
    let onSpeak: (String) -> Void
    let onStopSpeaking: () -> Void

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    if state.messages.isEmpty && state.tools.isEmpty {
                        ContentUnavailableView(
                            String(localized: "conversation.empty.title"),
                            systemImage: "sparkles",
                            description: Text(String(localized: "conversation.empty.description"))
                        )
                        .frame(maxWidth: .infinity, minHeight: 260)
                    }
                    ForEach(state.messages) { message in
                        MessageCard(
                            message: message,
                            isSpeaking: isSpeaking,
                            onSpeak: onSpeak,
                            onStopSpeaking: onStopSpeaking
                        )
                            .id(message.id)
                    }
                    ForEach(state.tools) { activity in
                        ToolActivityCard(activity: activity)
                            .id(activity.id)
                    }
                    if !state.statusText.isEmpty {
                        Text(state.statusText)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                }
                .padding(.horizontal)
                .padding(.vertical, 18)
            }
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: state.messages.count) { _, _ in
                if let last = state.messages.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
            }
        }
        .background(Color(uiColor: .systemGroupedBackground))
    }
}

private struct MessageCard: View {
    let message: ChatMessage
    let isSpeaking: Bool
    let onSpeak: (String) -> Void
    let onStopSpeaking: () -> Void

    private var isUser: Bool { message.role == .user }

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            if isUser { Spacer(minLength: 26) }
            VStack(alignment: .leading, spacing: 8) {
                Label(
                    isUser ? String(localized: "message.you") : String(localized: "message.hermes"),
                    systemImage: isUser ? "person.fill" : "sparkles"
                )
                .font(.caption.weight(.semibold))
                .foregroundStyle(isUser ? Color.accentColor : Color.primary)

                if !message.reasoning.isEmpty {
                    DisclosureGroup(String(localized: "message.reasoning")) {
                        Text(message.reasoning)
                            .font(.footnote.monospaced())
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                    }
                    .font(.footnote)
                }

                Text(message.text.isEmpty ? String(localized: "message.thinking") : message.text)
                    .textSelection(.enabled)
                    .foregroundStyle(message.text.isEmpty ? .secondary : .primary)

                if !isUser && !message.text.isEmpty {
                    Button {
                        isSpeaking ? onStopSpeaking() : onSpeak(message.text)
                    } label: {
                        Label(
                            isSpeaking ? String(localized: "action.stop.speaking") : String(localized: "action.speak"),
                            systemImage: isSpeaking ? "speaker.slash" : "speaker.wave.2"
                        )
                    }
                    .buttonStyle(.borderless)
                    .font(.footnote)
                    .tint(.primary)
                }
            }
            .padding(12)
            .frame(maxWidth: 680, alignment: .leading)
            .background(isUser ? Color.accentColor.opacity(0.12) : Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
            if !isUser { Spacer(minLength: 26) }
        }
        .accessibilityElement(children: .combine)
    }
}

private struct ToolActivityCard: View {
    let activity: ToolActivity

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label(activity.name, systemImage: activity.status == .complete ? "checkmark.circle" : "gearshape.2")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text(activity.status == .complete ? String(localized: "tool.complete") : String(localized: "tool.running"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if !activity.context.isEmpty { Text(activity.context).font(.footnote).foregroundStyle(.secondary) }
            if !activity.progress.isEmpty { Text(activity.progress).font(.footnote.monospaced()) }
            if !activity.result.isEmpty {
                DisclosureGroup(String(localized: "tool.result")) {
                    Text(activity.result)
                        .font(.footnote.monospaced())
                        .textSelection(.enabled)
                }
            }
        }
        .padding(12)
        .frame(maxWidth: 680, alignment: .leading)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 10))
        .accessibilityElement(children: .combine)
    }
}