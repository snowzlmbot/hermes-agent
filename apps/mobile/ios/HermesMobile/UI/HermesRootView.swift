import SwiftUI

struct HermesRootView: View {
    @Environment(AppModel.self) private var appModel

    var body: some View {
        Group {
            switch appModel.phase {
            case .loading:
                ProgressView(String(localized: "loading.app"))
                    .controlSize(.large)
                    .accessibilityLabel(String(localized: "loading.app"))
            case .onboarding:
                OnboardingView()
            case .connected:
                ChatShellView()
            }
        }
        .tint(.accentColor)
        .alert(
            String(localized: "error.title"),
            isPresented: Binding(
                get: { appModel.errorMessage != nil },
                set: { if !$0 { appModel.clearError() } }
            ),
            presenting: appModel.errorMessage
        ) { _ in
            Button(String(localized: "action.dismiss"), role: .cancel) { appModel.clearError() }
        } message: { message in
            Text(message)
        }
    }
}