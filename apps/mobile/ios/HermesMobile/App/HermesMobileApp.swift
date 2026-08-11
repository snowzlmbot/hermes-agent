import SwiftUI

@main
struct HermesMobileApp: App {
    @UIApplicationDelegateAdaptor(NotificationAppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @State private var appModel = AppModel()

    var body: some Scene {
        WindowGroup {
            HermesRootView()
                .environment(appModel)
                .task {
                    await appDelegate.install(appModel: appModel)
                    await appModel.bootstrap()
                }
                .onChange(of: scenePhase) { _, nextPhase in
                    switch nextPhase {
                    case .active:
                        appModel.setSceneActive(true)
                    case .background:
                        appModel.setSceneActive(false)
                    case .inactive:
                        break
                    @unknown default:
                        break
                    }
                }
        }
    }
}