import AVFoundation
import Foundation
import Observation

public enum AudioServiceError: Error, Equatable, Sendable {
    case microphoneDenied
    case recordingFailed
    case noRecording
    case playbackFailed
}

@MainActor
public protocol AudioRecordingServiceProtocol: AnyObject {
    func start() async throws
    func stop() throws -> AudioDataURL
    func cancel()
}

@MainActor
public protocol AudioPlaybackServiceProtocol: AnyObject {
    func play(_ audio: AudioDataURL) throws -> TimeInterval
    func stop()
}

public protocol AudioTranscriptionClient: Sendable {
    func transcription(dataURL: String, mimeType: String) async throws -> String
    func speech(text: String) async throws -> AudioDataURL
}

@MainActor
public final class AudioRecordingService {
    public static let maximumDuration: TimeInterval = 60
    private var recorder: AVAudioRecorder?
    private var recordingURL: URL?
    private var isStarting = false
    private var generation = 0

    public init() {}

    public func start() async throws {
        guard !isStarting, recorder == nil, recordingURL == nil else {
            throw AudioServiceError.recordingFailed
        }
        generation += 1
        let startGeneration = generation
        isStarting = true
        defer {
            if generation == startGeneration { isStarting = false }
        }
        let granted = await withCheckedContinuation { continuation in
            AVAudioApplication.requestRecordPermission { allowed in
                continuation.resume(returning: allowed)
            }
        }
        guard generation == startGeneration, !Task.isCancelled else { throw CancellationError() }
        guard granted else { throw AudioServiceError.microphoneDenied }

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(
            .playAndRecord,
            mode: .spokenAudio,
            options: [.defaultToSpeaker, .allowBluetoothHFP]
        )
        try session.setActive(true)

        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("hermes-voice-\(UUID().uuidString)")
            .appendingPathExtension("m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44_100,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
        ]
        let nextRecorder = try AVAudioRecorder(url: url, settings: settings)
        nextRecorder.prepareToRecord()
        guard nextRecorder.record(forDuration: Self.maximumDuration) else {
            try? FileManager.default.removeItem(at: url)
            throw AudioServiceError.recordingFailed
        }
        recorder = nextRecorder
        recordingURL = url
    }

    public func stop() throws -> AudioDataURL {
        guard let recorder, let recordingURL else { throw AudioServiceError.noRecording }
        recorder.stop()
        self.recorder = nil
        self.recordingURL = nil
        defer { try? FileManager.default.removeItem(at: recordingURL) }
        let data = try Data(contentsOf: recordingURL)
        return try AudioDataURL(mimeType: "audio/mp4", data: data)
    }

    public func cancel() {
        generation += 1
        isStarting = false
        recorder?.stop()
        recorder = nil
        if let recordingURL { try? FileManager.default.removeItem(at: recordingURL) }
        recordingURL = nil
    }
}

extension AudioRecordingService: AudioRecordingServiceProtocol {}

@MainActor
public final class AudioPlaybackService {
    private var player: AVAudioPlayer?

    public init() {}

    public func play(_ audio: AudioDataURL) throws -> TimeInterval {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .spokenAudio)
        try session.setActive(true)
        let player = try AVAudioPlayer(data: audio.data)
        player.prepareToPlay()
        guard player.play() else { throw AudioServiceError.playbackFailed }
        self.player = player
        return player.duration
    }

    public func stop() {
        player?.stop()
        player = nil
    }
}

extension AudioPlaybackService: AudioPlaybackServiceProtocol {}

@MainActor
@Observable
public final class AudioInteractionModel {
    public static let maximumRecordingDuration: Duration = .seconds(AudioRecordingService.maximumDuration)
    public private(set) var isRecording = false
    public private(set) var isStartingRecording = false
    public private(set) var isTranscribing = false
    public private(set) var isSpeaking = false
    public private(set) var errorMessage: String?

    @ObservationIgnored private let recordingService: any AudioRecordingServiceProtocol
    @ObservationIgnored private let playbackService: any AudioPlaybackServiceProtocol
    @ObservationIgnored private let maximumRecordingDuration: Duration
    @ObservationIgnored private var playbackTask: Task<Void, Never>?
    @ObservationIgnored private var recordingLimitTask: Task<Void, Never>?
    @ObservationIgnored private var recordingGeneration = 0

    public init(
        recordingService: any AudioRecordingServiceProtocol = AudioRecordingService(),
        playbackService: any AudioPlaybackServiceProtocol = AudioPlaybackService(),
        maximumRecordingDuration: Duration = AudioInteractionModel.maximumRecordingDuration
    ) {
        self.recordingService = recordingService
        self.playbackService = playbackService
        self.maximumRecordingDuration = maximumRecordingDuration
    }

    public func startRecording(
        using client: any AudioTranscriptionClient,
        onTranscript: @escaping @MainActor @Sendable (String) -> Void
    ) async {
        guard !isStartingRecording, !isRecording, !isTranscribing else { return }
        recordingGeneration += 1
        let startGeneration = recordingGeneration
        isStartingRecording = true
        defer { isStartingRecording = false }
        recordingLimitTask?.cancel()
        recordingLimitTask = nil
        do {
            errorMessage = nil
            try await recordingService.start()
            guard startGeneration == recordingGeneration, !Task.isCancelled else {
                recordingService.cancel()
                return
            }
            isRecording = true
            let limit = maximumRecordingDuration
            recordingLimitTask = Task { [weak self] in
                try? await Task.sleep(for: limit)
                guard !Task.isCancelled else { return }
                await self?.stopRecordingAtLimit(using: client, onTranscript: onTranscript)
            }
        } catch {
            isRecording = false
            if error is CancellationError { return }
            if let audioError = error as? AudioServiceError, audioError == .microphoneDenied {
                errorMessage = String(localized: "audio.microphone.error")
            } else {
                errorMessage = String(localized: "audio.recording.error")
            }
        }
    }

    public func stopAndTranscribe(using client: any AudioTranscriptionClient) async -> String? {
        guard isRecording else { return nil }
        recordingLimitTask?.cancel()
        recordingLimitTask = nil
        isRecording = false
        let audio: AudioDataURL
        do {
            audio = try recordingService.stop()
        } catch {
            errorMessage = String(localized: "audio.recording.error")
            return nil
        }
        isTranscribing = true
        defer { isTranscribing = false }
        do {
            return try await client.transcription(dataURL: audio.string, mimeType: audio.mimeType)
        } catch {
            errorMessage = String(localized: "audio.transcription.error")
            return nil
        }
    }

    public func cancelRecording() {
        recordingGeneration += 1
        recordingLimitTask?.cancel()
        recordingLimitTask = nil
        recordingService.cancel()
        isRecording = false
    }

    public func speak(_ text: String, using client: any AudioTranscriptionClient) async {
        let cleanText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanText.isEmpty else { return }
        do {
            errorMessage = nil
            let audio = try await client.speech(text: cleanText)
            let duration = try playbackService.play(audio)
            isSpeaking = true
            playbackTask?.cancel()
            playbackTask = Task { [weak self] in
                try? await Task.sleep(for: .seconds(duration))
                guard !Task.isCancelled else { return }
                self?.isSpeaking = false
            }
        } catch {
            isSpeaking = false
            errorMessage = String(localized: "audio.playback.error")
        }
    }

    public func stopSpeaking() {
        playbackTask?.cancel()
        playbackTask = nil
        playbackService.stop()
        isSpeaking = false
    }

    public func showTranscriptionError() {
        errorMessage = String(localized: "audio.transcription.error")
    }

    public func showPlaybackError() {
        errorMessage = String(localized: "audio.playback.error")
    }

    public func clearError() {
        errorMessage = nil
    }

    private func stopRecordingAtLimit(
        using client: any AudioTranscriptionClient,
        onTranscript: @escaping @MainActor @Sendable (String) -> Void
    ) async {
        guard isRecording else { return }
        recordingLimitTask = nil
        if let transcript = await stopAndTranscribe(using: client) {
            errorMessage = String(localized: "audio.recording.limit")
            onTranscript(transcript)
        }
    }
}
