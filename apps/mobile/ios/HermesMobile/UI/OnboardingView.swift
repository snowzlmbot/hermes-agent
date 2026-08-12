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
                            .foregroundStyle(.primary)
                    }
                    .padding(.vertical, 8)
                }

                Section {
                    Text(String(localized: "connection.section"))
                        .font(.headline)
                        .foregroundStyle(.primary)
                        .accessibilityAddTraits(.isHeader)

                    VStack(alignment: .leading, spacing: 6) {
                        Text(String(localized: "connection.address"))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.primary)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityHidden(true)
                        TextField("", text: $address, axis: .vertical)
                            .lineLimit(1...3)
                            .fixedSize(horizontal: false, vertical: true)
                            .font(.body.weight(.semibold))
                            .foregroundStyle(.primary)
                            .textContentType(.URL)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .accessibilityLabel(String(localized: "connection.address"))
                            .accessibilityIdentifier("Gateway address")
                    }
                    .padding(.vertical, 4)

                    SecureField(
                        "",
                        text: $token,
                        prompt: Text(String(localized: "connection.token"))
                            .foregroundStyle(.primary)
                    )
                    .padding(.vertical, 8)
                    .font(.body.weight(.medium))
                    .foregroundStyle(.primary)
                    .textContentType(.password)
                    .privacySensitive()
                    .accessibilityLabel(String(localized: "connection.token"))
                    .accessibilityIdentifier("Gateway token")

                    if appModel.allowsInsecureTransport {
                        Toggle(isOn: $allowInsecure) {
                            Text(String(localized: "connection.allow.insecure"))
                                .font(.body.weight(.semibold))
                                .foregroundStyle(.primary)
                                .lineLimit(nil)
                                .multilineTextAlignment(.leading)
                                .layoutPriority(1)
                        }
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .padding(.vertical, 4)
                        .accessibilityIdentifier("Allow insecure HTTP")
                    }
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
                        .foregroundStyle(.primary)
                    }
                    .tint(.primary)
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