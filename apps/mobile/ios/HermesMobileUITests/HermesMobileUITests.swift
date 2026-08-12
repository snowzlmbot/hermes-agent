import XCTest

@MainActor
final class HermesMobileUITests: XCTestCase {
    func testSetupScreenExposesAccessibleConnectionControls() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-smoke"]
        app.launch()

        XCTAssertTrue(app.staticTexts["Connect to Hermes"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.textFields["Gateway address"].exists)
        XCTAssertTrue(app.secureTextFields["Gateway token"].exists)
        XCTAssertTrue(app.buttons["Connect"].exists)
        XCTAssertTrue(app.switches["Allow insecure HTTP"].exists)
    }

    func testDemoModeShowsAdaptiveSessionAndComposerSurfaces() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-demo"]
        app.launch()

        XCTAssertTrue(app.navigationBars["Sessions"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Welcome to Hermes"].exists)
        XCTAssertTrue(app.buttons["Read aloud"].exists)
        XCTAssertTrue(app.textViews["Message Hermes"].exists)
        XCTAssertTrue(app.buttons["Send message"].exists)
        XCTAssertTrue(app.buttons["Attach file"].exists)

        let controls = app.descendants(matching: .any)["Model controls"]
        XCTAssertTrue(controls.waitForExistence(timeout: 3))
        controls.tap()
        XCTAssertTrue(app.navigationBars["Model controls"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["Model"].exists)
        XCTAssertTrue(app.staticTexts["Reasoning"].exists)
    }
}
