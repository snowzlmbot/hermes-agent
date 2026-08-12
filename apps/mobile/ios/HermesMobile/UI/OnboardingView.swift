import SwiftUI

struct OnboardingView: View {
    @Environment(AppModel.self) private var appModel
    @State private var address = "https://"
    @State private var token = ""
    @State private var allowInsecure = false
    @State private var selectedProvider = ""

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

                Section {
                    TextField(
                        String(localized: "connection.address"),
                        text: $address
                    )
                    .frame(minHeight: 44, alignment: .leading)
                    .font(.body.weight(.medium))
                    .foregroundStyle(.primary)
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("Gateway address")

                    SecureField(String(localized: "connection.token"), text: $token)
                        .frame(minHeight: 44, alignment: .leading)
                        .font(.body.weight(.medium))
                        .foregroundStyle(.primary)
                        .textContentType(.password)
                        .accessibilityIdentifier("Gateway token")

                    if appModel.allowsInsecureTransport {
                        Toggle(isOn: $allowInsecure) {
                            Text(String(localized: "connection.allow.insecure"))
                                .fontWeight(.medium)
                                .foregroundStyle(.primary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(minHeight: 44, alignment: .leading)
                        .accessibilityIdentifier("Allow insecure HTTP")
                    }
                } header: {
                    Text(String(localized: "connection.section"))
                        .font(.headline)
                        .foregroundStyle(.primary)
                        .textCase(nil)
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
                    OAuthCapabilityPanel(
                        capability: capability,
                        providers: appModel.oauthProviders,
                        selectedProvider: $selectedProvider,
                        isConnecting: appModel.isConnecting,
                        onSignIn: {
                            Task {
                                await appModel.signInWithOAuth(
                                    address: address,
                                    provider: selectedProvider
                                )
                            }
                        }
                    )
                }
            }
            .navigationTitle(String(localized: "app.name"))
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct OAuthCapabilityPanel: View {
    let capability: NativeOAuthCapability
    let providers: [NativeOAuthProvider]
    @Binding var selectedProvider: String
    let isConnecting: Bool
    let onSignIn: () -> Void

    var body: some View {
        Section(String(localized: "oauth.section")) {
            Label(
                capability.state == .available
                    ? String(localized: "oauth.available.title")
                    : String(localized: "oauth.unavailable.title"),
                systemImage: capability.state == .available ? "person.badge.key.fill" : "exclamationmark.shield"
            )
            .foregroundStyle(capability.state == .available ? .green : .orange)
            Text(capability.explanation)
                .font(.footnote)
                .foregroundStyle(.secondary)
            if capability.state == .available {
                if providers.count > 1 {
                    Picker(String(localized: "oauth.provider"), selection: $selectedProvider) {
                        Text(String(localized: "oauth.provider.select")).tag("")
                        ForEach(providers, id: \.name) { provider in
                            Text(provider.displayName).tag(provider.name)
                        }
                    }
                }
                Button(action: onSignIn) {
                    Label(String(localized: "action.oauth.signin"), systemImage: "person.badge.key")
                        .frame(maxWidth: .infinity)
                }
                .disabled(isConnecting || providers.isEmpty || (providers.count > 1 && selectedProvider.isEmpty))
                .accessibilityIdentifier("Sign in with OAuth")
            } else {
                Text(String(localized: "oauth.token.guidance"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }
}