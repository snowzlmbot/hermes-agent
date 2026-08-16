import Foundation
import XCTest
@testable import HermesMobile

@MainActor
final class AudioInteractionModelTests: XCTestCase {
    func testPermissionDenialSurfacesLocalizedMicrophoneError() async {
        let recorder = FakeRecordingService(startError: AudioServiceError.microphoneDenied)
        let model = AudioInteractionModel(
            recordingService: recorder,
            playbackService: FakePlaybackService()
        )

        await model.startRecording(using: FakeAudioClient()) { _ in }

        XCTAssertFalse(model.isRecording)
        XCTAssertEqual(model.errorMessage, String(localized: "audio.microphone.error"))
    }

    func testRecordingFailureSurfacesDistinctLocalizedError() async {
        let recorder = FakeRecordingService(startError: AudioServiceError.recordingFailed)
        let model = AudioInteractionModel(
            recordingService: recorder,
            playbackService: FakePlaybackService()
        )

        await model.startRecording(using: FakeAudioClient()) { _ in }

        XCTAssertFalse(model.isRecording)
        XCTAssertEqual(model.errorMessage, String(localized: "audio.recording.error"))
    }

    func testTranscriptionFailureStopsProgressAndSurfacesLocalizedError() async {
        let recorder = FakeRecordingService()
        let client = FakeAudioClient(transcriptionError: FakeError.failed)
        let model = AudioInteractionModel(
            recordingService: recorder,
            playbackService: FakePlaybackService()
        )
        await model.startRecording(using: FakeAudioClient()) { _ in }

        let transcript = await model.stopAndTranscribe(using: client)

        XCTAssertNil(transcript)
        XCTAssertFalse(model.isRecording)
        XCTAssertFalse(model.isTranscribing)
        XCTAssertEqual(model.errorMessage, String(localized: "audio.transcription.error"))
    }

    func testRecorderStopFailureSurfacesRecordingErrorWithoutCallingTranscription() async {
        let recorder = FakeRecordingService(stopError: AudioServiceError.recordingFailed)
        let client = FakeAudioClient()
        let model = AudioInteractionModel(
            recordingService: recorder,
            playbackService: FakePlaybackService()
        )
        await model.startRecording(using: FakeAudioClient()) { _ in }

        let transcript = await model.stopAndTranscribe(using: client)
        let transcriptionCallCount = await client.transcriptionCallCount

        XCTAssertNil(transcript)
        XCTAssertFalse(model.isTranscribing)
        XCTAssertEqual(model.errorMessage, String(localized: "audio.recording.error"))
        XCTAssertEqual(transcriptionCallCount, 0)
    }

    func testPlaybackFailureSurfacesLocalizedError() async {
        let player = FakePlaybackService(playError: AudioServiceError.playbackFailed)
        let model = AudioInteractionModel(
            recordingService: FakeRecordingService(),
            playbackService: player
        )

        await model.speak("Read this", using: FakeAudioClient())

        XCTAssertFalse(model.isSpeaking)
        XCTAssertEqual(model.errorMessage, String(localized: "audio.playback.error"))
    }

    func testRecordingAutomaticallyStopsAtConfiguredDuration() async throws {
        let recorder = FakeRecordingService()
        XCTAssertEqual(
            AudioInteractionModel.maximumRecordingDuration,
            .seconds(AudioRecordingService.maximumDuration)
        )
        let model = AudioInteractionModel(
            recordingService: recorder,
            playbackService: FakePlaybackService(),
            maximumRecordingDuration: .milliseconds(10)
        )

        var automaticTranscript: String?
        await model.startRecording(using: FakeAudioClient(transcript: "limit transcript")) { text in
            automaticTranscript = text
        }
        try await Task.sleep(for: .milliseconds(50))

        XCTAssertFalse(model.isRecording)
        XCTAssertEqual(recorder.stopCallCount, 1)
        XCTAssertEqual(automaticTranscript, "limit transcript")
        XCTAssertEqual(model.errorMessage, String(localized: "audio.recording.limit"))
    }

    func testSuccessfulRecordingTranscriptionAndPlaybackPathsRemainAvailable() async {
        let recorder = FakeRecordingService()
        let player = FakePlaybackService(duration: 60)
        let client = FakeAudioClient(transcript: "hello")
        let model = AudioInteractionModel(recordingService: recorder, playbackService: player)

        await model.startRecording(using: FakeAudioClient()) { _ in }
        let transcript = await model.stopAndTranscribe(using: client)
        await model.speak("Reply", using: client)

        XCTAssertEqual(transcript, "hello")
        XCTAssertTrue(model.isSpeaking)
        XCTAssertNil(model.errorMessage)
        XCTAssertEqual(recorder.startCallCount, 1)
        XCTAssertEqual(recorder.stopCallCount, 1)
        XCTAssertEqual(player.playCallCount, 1)
        model.stopSpeaking()
    }
}

@MainActor
private final class FakeRecordingService: AudioRecordingServiceProtocol {
    private let startError: Error?
    private let stopError: Error?
    private(set) var startCallCount = 0
    private(set) var stopCallCount = 0

    init(startError: Error? = nil, stopError: Error? = nil) {
        self.startError = startError
        self.stopError = stopError
    }

    func start() async throws {
        startCallCount += 1
        if let startError { throw startError }
    }

    func stop() throws -> AudioDataURL {
        stopCallCount += 1
        if let stopError { throw stopError }
        return try AudioDataURL(mimeType: "audio/mp4", data: Data("audio".utf8))
    }

    func cancel() {}
}

@MainActor
private final class FakePlaybackService: AudioPlaybackServiceProtocol {
    private let duration: TimeInterval
    private let playError: Error?
    private(set) var playCallCount = 0

    init(duration: TimeInterval = 1, playError: Error? = nil) {
        self.duration = duration
        self.playError = playError
    }

    func play(_ audio: AudioDataURL) throws -> TimeInterval {
        playCallCount += 1
        if let playError { throw playError }
        return duration
    }

    func stop() {}
}

private actor FakeAudioClient: AudioTranscriptionClient {
    private let transcript: String
    private let transcriptionError: FakeError?
    private(set) var transcriptionCallCount = 0

    init(transcript: String = "transcript", transcriptionError: FakeError? = nil) {
        self.transcript = transcript
        self.transcriptionError = transcriptionError
    }

    func transcription(dataURL: String, mimeType: String) async throws -> String {
        transcriptionCallCount += 1
        if let transcriptionError { throw transcriptionError }
        return transcript
    }

    func speech(text: String) async throws -> AudioDataURL {
        try AudioDataURL(mimeType: "audio/mp4", data: Data("speech".utf8))
    }
}

private enum FakeError: Error, Sendable {
    case failed
}
