import XCTest

@MainActor
final class HermesMobileUITests: XCTestCase {
    func testSetupScreenExposesAccessibleConnectionControls() throws {
        let app = XCUIApplication()
        app.launchArguments = [
            "--ui-smoke",
            "-UIPreferredContentSizeCategoryName",
            "UICTContentSizeCategoryAccessibilityXXXL",
        ]
        app.launch()

        XCTAssertTrue(app.staticTexts["Connect to Hermes"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.textFields["Gateway address"].exists)
        try app.performAccessibilityAudit()

        let tokenField = app.secureTextFields["Gateway token"]
        XCTAssertTrue(reveal(tokenField, in: app))

        let insecureTransportToggle = app.switches["Allow insecure HTTP"]
        XCTAssertTrue(reveal(insecureTransportToggle, in: app))
        XCTAssertEqual(insecureTransportToggle.label, "Allow insecure HTTP")
        try app.performAccessibilityAudit()

        XCTAssertTrue(reveal(app.buttons["Connect"], in: app))
        try app.performAccessibilityAudit()
    }

    func testDemoModeShowsAdaptiveSessionAndComposerSurfaces() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-demo"]
        app.launch()

        XCTAssertTrue(app.navigationBars["Sessions"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Welcome to Hermes"].exists)
        XCTAssertTrue(app.buttons["Read aloud"].exists)
        let composer = app.textViews["Message Hermes"]
        XCTAssertTrue(composer.exists)
        XCTAssertEqual(composer.label, "Message Hermes")
        XCTAssertTrue(app.buttons["Send message"].exists)
        XCTAssertTrue(app.buttons["Attach file"].exists)
        try app.performAccessibilityAudit()

        let controls = app.descendants(matching: .any)["Model controls"]
        XCTAssertTrue(controls.waitForExistence(timeout: 3))
        controls.tap()
        XCTAssertTrue(app.navigationBars["Model controls"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["Model"].exists)
        XCTAssertTrue(app.staticTexts["Reasoning"].exists)
    }

    private func reveal(
        _ element: XCUIElement,
        in app: XCUIApplication,
        maxSwipes: Int = 6
    ) -> Bool {
        for _ in 0..<maxSwipes {
            if element.exists && element.isHittable {
                return true
            }
            app.swipeUp()
        }
        return element.waitForExistence(timeout: 2) && element.isHittable
    }
}
