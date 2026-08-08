import SwiftUI

struct ModelControlsSheet: View {
    let chat: ChatModel

    @Environment(\.dismiss) private var dismiss

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
                                Task { try? await chat.selectModel(option) }
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
                                Task { try? await chat.setReasoningEffort(effort) }
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
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "action.done")) { dismiss() }
                }
            }
            .task {
                if chat.modelCatalog.providers.isEmpty {
                    try? await chat.loadModelOptions()
                }
            }
        }
    }
}