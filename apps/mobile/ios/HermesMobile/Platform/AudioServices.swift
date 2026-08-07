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
public final class AudioRecordingService {
    private var recorder: AVAudioRecorder?
    private var recordingURL: URL?

    public init() {}

    public func start() async throws {
        let granted = await withCheckedContinuation { continuation in
            AVAudioApplication.requestRecordPermission { allowed in
                continuation.resume(returning: allowed)
            }
        }
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
        guard nextRecorder.record() else { throw AudioServiceError.recordingFailed }
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
        recorder?.stop()
        recorder = nil
        if let recordingURL { try? FileManager.default.removeItem(at: recordingURL) }
        recordingURL = nil
    }
}

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

@MainActor
@Observable
public final class AudioInteractionModel {
    public private(set) var isRecording = false
    public private(set) var isTranscribing = false
    public private(set) var isSpeaking = false
    public private(set) var errorMessage: String?

    @ObservationIgnored private let recordingService: AudioRecordingService
    @ObservationIgnored private let playbackService: AudioPlaybackService
    @ObservationIgnored private var playbackTask: Task<Void, Never>?

    public init(
        recordingService: AudioRecordingService = AudioRecordingService(),
        playbackService: AudioPlaybackService = AudioPlaybackService()
    ) {
        self.recordingService = recordingService
        self.playbackService = playbackService
    }

    public func startRecording() async {
        do {
            errorMessage = nil
            try await recordingService.start()
            isRecording = true
        } catch {
            isRecording = false
            errorMessage = String(localized: "audio.microphone.error")
        }
    }

    public func stopAndTranscribe(using client: GatewayRESTClient) async -> String? {
        guard isRecording else { return nil }
        isRecording = false
        isTranscribing = true
        defer { isTranscribing = false }
        do {
            let audio = try recordingService.stop()
            return try await client.transcription(dataURL: audio.string, mimeType: audio.mimeType)
        } catch {
            errorMessage = String(localized: "audio.transcription.error")
            return nil
        }
    }

    public func cancelRecording() {
        recordingService.cancel()
        isRecording = false
    }

    public func speak(_ text: String, using client: GatewayRESTClient) async {
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
}