import Foundation
import UniformTypeIdentifiers
import XCTest
@testable import HermesMobile

@MainActor
final class MediaImportErrorTests: XCTestCase {
    func testPhotoImporterFailureSurfacesLocalizedError() async {
        let model = AppModel(dependencies: AppDependencies(
            profileRepository: GatewayProfileRepository(
                profileStore: InMemoryGatewayProfileStore(),
                credentialStore: InMemoryCredentialStore()
            ),
            notificationService: MediaNoopNotificationService(),
            attachmentImporter: FailingAttachmentImporter()
        ))
        await model.bootstrap(arguments: ["--ui-demo"])

        await model.attachPhoto(data: Data([1]), contentType: .png)

        XCTAssertEqual(model.errorMessage, String(localized: "error.attachment.import"))
    }

    func testPickerTransferFailureCanSurfaceSameLocalizedError() {
        let model = AppModel(dependencies: AppDependencies(
            profileRepository: GatewayProfileRepository(
                profileStore: InMemoryGatewayProfileStore(),
                credentialStore: InMemoryCredentialStore()
            ),
            notificationService: MediaNoopNotificationService(),
            attachmentImporter: FailingAttachmentImporter()
        ))

        model.reportAttachmentImportError()

        XCTAssertEqual(model.errorMessage, String(localized: "error.attachment.import"))
    }
}

private struct FailingAttachmentImporter: AttachmentImporting {
    func imagePayload(data: Data, contentType: UTType?) async throws -> AttachmentPayload {
        throw AttachmentError.unsupportedType
    }

    func filePayload(at url: URL) async throws -> AttachmentPayload {
        throw AttachmentError.unsupportedType
    }
}

private actor MediaNoopNotificationService: NotificationScheduling {
    func requestAuthorization() async -> Bool { false }
    func scheduleCompletion(sessionTitle: String, route: NotificationRoute) async {}
    func scheduleApproval(route: NotificationRoute) async {}
    func scheduleInput(route: NotificationRoute) async {}
}
