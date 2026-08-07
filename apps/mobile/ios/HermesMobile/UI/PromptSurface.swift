import SwiftUI

struct PromptSurface: View {
    let chat: ChatModel

    var body: some View {
        VStack(spacing: 10) {
            if let approval = chat.state.approval {
                ApprovalCard(prompt: approval) { choice in
                    Task { try? await chat.respondToApproval(choice) }
                }
            }
            if let clarify = chat.state.clarify {
                ClarifyCard(prompt: clarify) { answer in
                    Task { try? await chat.respondToClarify(requestID: clarify.requestID, answer: answer) }
                }
            }
            if let secret = chat.state.secret {
                SecretCard(prompt: secret.question) { value in
                    Task { try? await chat.respondToSecret(requestID: secret.requestID, value: value) }
                }
            }
            if let sudo = chat.state.sudo {
                SecretCard(
                    title: String(localized: "sudo.title"),
                    prompt: String(localized: "sudo.prompt"),
                    submitLabel: String(localized: "action.continue")
                ) { value in
                    Task { try? await chat.respondToSudo(requestID: sudo.requestID, password: value) }
                }
            }
        }
        .padding(.horizontal)
        .padding(.bottom, 8)
    }
}

private struct ApprovalCard: View {
    let prompt: ApprovalPrompt
    let onChoice: (ApprovalChoice) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(String(localized: "approval.title"), systemImage: "hand.raised.fill")
                .font(.headline)
            if !prompt.description.isEmpty { Text(prompt.description).font(.subheadline) }
            if !prompt.command.isEmpty {
                Text(prompt.command)
                    .font(.footnote.monospaced())
                    .lineLimit(4)
                    .textSelection(.enabled)
                    .padding(8)
                    .background(.black.opacity(0.06), in: RoundedRectangle(cornerRadius: 6))
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 8)], spacing: 8) {
                ForEach(prompt.choices) { choice in
                    Button(choice.displayName) { onChoice(choice) }
                        .buttonStyle(.bordered)
                        .accessibilityLabel(choice.displayName)
                }
            }
        }
        .padding(12)
        .frame(maxWidth: 680, alignment: .leading)
        .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
        .accessibilityElement(children: .contain)
    }
}

private struct ClarifyCard: View {
    let prompt: ClarifyPrompt
    let onAnswer: (String) -> Void
    @State private var answer = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(String(localized: "clarify.title"), systemImage: "questionmark.circle.fill")
                .font(.headline)
            Text(prompt.question)
            if !prompt.choices.isEmpty {
                ForEach(prompt.choices, id: \.self) { choice in
                    Button {
                        answer = choice
                        onAnswer(choice)
                    } label: {
                        HStack {
                            Text(choice)
                            Spacer()
                            Image(systemName: "arrow.up.right")
                        }
                    }
                    .buttonStyle(.bordered)
                }
            }
            HStack {
                TextField(String(localized: "clarify.other"), text: $answer)
                    .textFieldStyle(.roundedBorder)
                Button(String(localized: "action.submit")) { onAnswer(answer) }
                    .disabled(answer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(12)
        .frame(maxWidth: 680, alignment: .leading)
        .background(Color.blue.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
    }
}

private struct SecretCard: View {
    let title: String
    let prompt: String
    let submitLabel: String
    let onSubmit: (String) -> Void
    @State private var value = ""

    init(
        title: String = String(localized: "secret.title"),
        prompt: String,
        submitLabel: String = String(localized: "action.submit"),
        onSubmit: @escaping (String) -> Void
    ) {
        self.title = title
        self.prompt = prompt
        self.submitLabel = submitLabel
        self.onSubmit = onSubmit
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(title, systemImage: "key.fill")
                .font(.headline)
            Text(prompt)
                .font(.subheadline)
            SecureField(String(localized: "secret.value"), text: $value)
                .textFieldStyle(.roundedBorder)
                .textContentType(.password)
            Button(submitLabel) {
                let submitted = value
                value = ""
                onSubmit(submitted)
            }
            .disabled(value.isEmpty)
        }
        .padding(12)
        .frame(maxWidth: 680, alignment: .leading)
        .background(Color.purple.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
    }
}