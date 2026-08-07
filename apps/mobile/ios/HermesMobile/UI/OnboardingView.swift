import SwiftUI

struct OnboardingView: View {
    @Environment(AppModel.self) private var appModel
    @State private var address = "https://"
    @State private var token = ""
    @State private var allowInsecure = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Label(String(localized: "onboarding.title"), systemImage: "bolt.horizontal.circle.fill")
                            .font(.title2.weight(.semibold))
                        Text(String(localized: "onboarding.subtitle"))
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 8)
                }

                Section(String(localized: "connection.section")) {
                    TextField(
                        String(localized: "connection.address"),
                        text: $address
                    )
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("Gateway address")

                    SecureField(String(localized: "connection.token"), text: $token)
                        .textContentType(.password)
                        .accessibilityIdentifier("Gateway token")

                    Toggle(String(localized: "connection.allow.insecure"), isOn: $allowInsecure)
                        .accessibilityIdentifier("Allow insecure HTTP")
                }

                Section {
                    Button {
                        Task { await appModel.connect(address: address, token: token, allowInsecure: allowInsecure) }
                    } label: {
                        Label(
                            appModel.isConnecting
                                ? String(localized: "connection.connecting")
                                : String(localized: "action.connect"),
                            systemImage: appModel.isConnecting ? "arrow.triangle.2.circlepath" : "link"
                        )
                        .frame(maxWidth: .infinity)
                    }
                    .disabled(appModel.isConnecting)
                    .accessibilityIdentifier("Connect")
                }

                if let capability = appModel.oauthCapability {
                    OAuthCapabilityPanel(capability: capability)
                }
            }
            .navigationTitle(String(localized: "app.name"))
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct OAuthCapabilityPanel: View {
    let capability: NativeOAuthCapability

    var body: some View {
        Section(String(localized: "oauth.section")) {
            Label(
                capability.state == .loopbackOnly
                    ? String(localized: "oauth.loopback.title")
                    : String(localized: "oauth.unavailable.title"),
                systemImage: "exclamationmark.shield"
            )
            .foregroundStyle(.orange)
            Text(capability.explanation)
                .font(.footnote)
                .foregroundStyle(.secondary)
            Text(String(localized: "oauth.token.guidance"))
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}