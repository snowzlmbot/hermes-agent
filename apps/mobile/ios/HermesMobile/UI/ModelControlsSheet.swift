import SwiftUI

struct ModelControlsSheet: View {
    let chat: ChatModel

    @Environment(\.dismiss) private var dismiss
    @State private var operationError: String?

    private let reasoningEfforts = ["none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"]

    private var modelOptions: [ModelOption] {
        chat.modelCatalog.providers.flatMap { $0.models }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if modelOptions.isEmpty {
                        Text(String(localized: "model.controls.none"))
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(modelOptions, id: \.self) { option in
                            Button {
                                select(option)
                            } label: {
                                HStack(alignment: .top, spacing: 12) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(option.modelID)
                                        Text(option.providerName)
                                            .font(.footnote)
                                            .foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    if chat.selectedModelID == option.modelID && chat.selectedProviderID == option.providerID {
                                        Image(systemName: "checkmark")
                                    }
                                }
                            }
                        }
                    }
                } header: {
                    Text(String(localized: "model.controls.model"))
                }

                Section {
                    if let selected = modelOptions.first(where: {
                        $0.modelID == chat.selectedModelID && $0.providerID == chat.selectedProviderID
                    }), selected.supportsReasoning {
                        ForEach(reasoningEfforts, id: \.self) { effort in
                            Button {
                                setReasoning(effort)
                            } label: {
                                HStack {
                                    Text(effort)
                                    Spacer()
                                    if chat.reasoningEffort == effort {
                                        Image(systemName: "checkmark")
                                    }
                                }
                            }
                        }
                    } else {
                        Text(String(localized: "model.controls.reasoning.unavailable"))
                            .foregroundStyle(.secondary)
                    }
                } header: {
                    Text(String(localized: "model.controls.reasoning"))
                }
            }
            .navigationTitle(String(localized: "model.controls.title"))
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        refresh()
                    } label: {
                        if chat.isLoadingModelOptions {
                            ProgressView()
                        } else {
                            Label(String(localized: "model.controls.refresh"), systemImage: "arrow.clockwise")
                                .labelStyle(.iconOnly)
                        }
                    }
                    .disabled(chat.isLoadingModelOptions)
                    .accessibilityLabel(String(localized: "model.controls.refresh"))
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "action.done")) { dismiss() }
                }
            }
            .task {
                if chat.modelCatalog.providers.isEmpty {
                    await loadModels(refresh: false)
                }
            }
            .confirmationDialog(
                String(localized: "model.confirm.title"),
                isPresented: Binding(
                    get: { chat.pendingModelConfirmation != nil },
                    set: { if !$0 { chat.cancelPendingModelSelection() } }
                ),
                titleVisibility: .visible,
                presenting: chat.pendingModelConfirmation
            ) { pending in
                Button(String(localized: "action.continue")) { confirmSelection(pending) }
                Button(String(localized: "action.cancel"), role: .cancel) {
                    chat.cancelPendingModelSelection()
                }
            } message: { pending in
                Text(pending.message)
            }
            .alert(
                String(localized: "error.title"),
                isPresented: Binding(
                    get: { operationError != nil },
                    set: { if !$0 { operationError = nil } }
                ),
                presenting: operationError
            ) { _ in
                Button(String(localized: "action.dismiss"), role: .cancel) { operationError = nil }
            } message: { message in
                Text(message)
            }
        }
    }

    private func select(_ option: ModelOption) {
        Task {
            do {
                _ = try await chat.selectModel(option)
            } catch {
                operationError = chat.controlErrorMessage ?? String(localized: "error.model.switch")
            }
        }
    }

    private func setReasoning(_ effort: String) {
        Task {
            do {
                try await chat.setReasoningEffort(effort)
            } catch {
                operationError = chat.controlErrorMessage ?? String(localized: "error.model.reasoning")
            }
        }
    }

    private func refresh() {
        Task { await loadModels(refresh: true) }
    }

    private func loadModels(refresh: Bool) async {
        do {
            try await chat.loadModelOptions(refresh: refresh)
        } catch {
            operationError = chat.controlErrorMessage ?? String(localized: "error.model.options")
        }
    }

    private func confirmSelection(_ pending: PendingModelConfirmation) {
        Task {
            do {
                try await chat.confirmModelSelection(pending)
            } catch {
                operationError = chat.controlErrorMessage ?? String(localized: "error.model.switch")
            }
        }
    }
}
