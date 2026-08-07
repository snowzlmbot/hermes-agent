import SwiftUI

@main
struct HermesMobileApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var appModel = AppModel()

    var body: some Scene {
        WindowGroup {
            HermesRootView()
                .environment(appModel)
                .task { await appModel.bootstrap() }
                .onChange(of: scenePhase) { _, nextPhase in
                    appModel.setSceneActive(nextPhase == .active)
                }
        }
    }
}