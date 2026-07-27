import SwiftUI
import AVFoundation
import MediaPlayer
import UIKit

// MARK: - 听书

@MainActor
final class SpeechController: NSObject, ObservableObject {
    @Published var isSpeaking = false
    /// 当前朗读到的句子（章节内偏移），用于正文跟读高亮
    @Published var spokenRange: NSRange?
    @Published var sleepTimerMinutes: Int = 0
    @Published var sleepRemaining: Int = 0

    private let synthesizer = AVSpeechSynthesizer()
    private var baseOffset = 0
    /// 一段读完后回调，用于自动续读下一章
    var onFinish: (() -> Void)?
    /// 朗读位置越过当前页时回调，用于自动翻页跟随
    var onProgress: ((Int) -> Void)?

    private var timer: Timer?

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    static var chineseVoices: [AVSpeechSynthesisVoice] {
        AVSpeechSynthesisVoice.speechVoices()
            .filter { $0.language.hasPrefix("zh") }
            .sorted { $0.quality.rawValue > $1.quality.rawValue }
    }

    func speak(text: String, baseOffset: Int, config: ReaderConfig) {
        stop(clearRange: false)
        guard !text.isEmpty else { return }
        configureAudioSession()
        self.baseOffset = baseOffset

        let utterance = AVSpeechUtterance(string: text)
        // 0...1 映射到可用语速区间，0.5 大约是正常语速
        let minRate = AVSpeechUtteranceMinimumSpeechRate
        let maxRate = AVSpeechUtteranceMaximumSpeechRate
        let normal = AVSpeechUtteranceDefaultSpeechRate
        let s = Float(config.speechRate)
        utterance.rate = s <= 0.5
            ? minRate + (normal - minRate) * (s / 0.5)
            : normal + (maxRate - normal) * ((s - 0.5) / 0.5)
        utterance.pitchMultiplier = Float(config.speechPitch)
        utterance.postUtteranceDelay = 0.05
        if !config.speechVoiceID.isEmpty, let v = AVSpeechSynthesisVoice(identifier: config.speechVoiceID) {
            utterance.voice = v
        } else {
            utterance.voice = Self.chineseVoices.first ?? AVSpeechSynthesisVoice(language: "zh-CN")
        }
        isSpeaking = true
        synthesizer.speak(utterance)
    }

    func pause() {
        synthesizer.pauseSpeaking(at: .word)
        isSpeaking = false
    }

    func resume() {
        if synthesizer.isPaused {
            synthesizer.continueSpeaking()
            isSpeaking = true
        }
    }

    func stop(clearRange: Bool = true) {
        synthesizer.stopSpeaking(at: .immediate)
        isSpeaking = false
        if clearRange { spokenRange = nil }
        timer?.invalidate()
        timer = nil
        sleepRemaining = 0
        sleepTimerMinutes = 0
    }

    func startSleepTimer(minutes: Int) {
        timer?.invalidate()
        sleepTimerMinutes = minutes
        guard minutes > 0 else { sleepRemaining = 0; return }
        sleepRemaining = minutes * 60
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.sleepRemaining -= 1
                if self.sleepRemaining <= 0 { self.stop() }
            }
        }
    }

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
        try? session.setActive(true)
    }
}

extension SpeechController: AVSpeechSynthesizerDelegate {
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer,
                                       willSpeakRangeOfSpeechString characterRange: NSRange,
                                       utterance: AVSpeechUtterance) {
        let range = characterRange
        Task { @MainActor in
            self.spokenRange = NSRange(location: self.baseOffset + range.location, length: range.length)
            self.onProgress?(self.baseOffset + range.location)
        }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer,
                                       didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in
            self.isSpeaking = false
            self.onFinish?()
        }
    }
}

// MARK: - 自动阅读

@MainActor
final class AutoReadController: ObservableObject {
    @Published var isRunning = false
    /// 0...1，越大越快
    @Published var speed: Double = 0.5

    private var displayLink: CADisplayLink?
    private var accumulator: CGFloat = 0
    var onStep: (() -> Void)?          // 定时翻页
    var onScroll: ((CGFloat) -> Void)? // 平滑滚动，参数为本帧位移

    private var pageInterval: CGFloat { CGFloat(11.5 - speed * 9.5) }   // 秒/页
    private var scrollSpeed: CGFloat { CGFloat(14 + speed * 96) }        // 点/秒

    func start(mode: AutoReadMode) {
        stop()
        isRunning = true
        accumulator = 0
        let link = CADisplayLink(target: DisplayLinkProxy { [weak self] dt in
            MainActor.assumeIsolated { self?.tick(delta: dt, mode: mode) }
        }, selector: #selector(DisplayLinkProxy.handle(_:)))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 60, preferred: 60)
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    func stop() {
        displayLink?.invalidate()
        displayLink = nil
        isRunning = false
    }

    private func tick(delta: CFTimeInterval, mode: AutoReadMode) {
        guard isRunning else { return }
        switch mode {
        case .pageByPage:
            accumulator += CGFloat(delta)
            if accumulator >= pageInterval {
                accumulator = 0
                onStep?()
            }
        case .smoothScroll:
            onScroll?(scrollSpeed * CGFloat(delta))
        }
    }
}

/// CADisplayLink 需要 ObjC target，用一个小代理包一层
private final class DisplayLinkProxy: NSObject {
    private let handler: (CFTimeInterval) -> Void
    private var last: CFTimeInterval = 0

    init(handler: @escaping (CFTimeInterval) -> Void) {
        self.handler = handler
    }

    @objc func handle(_ link: CADisplayLink) {
        let now = link.timestamp
        let delta = last == 0 ? 1.0 / 60.0 : min(0.1, now - last)
        last = now
        handler(delta)
    }
}

// MARK: - 亮度接管

@MainActor
final class BrightnessManager: ObservableObject {
    private var original: CGFloat?

    func apply(custom: Double, follow: Bool) {
        if follow {
            restore()
        } else {
            if original == nil { original = UIScreen.main.brightness }
            UIScreen.main.brightness = CGFloat(min(1, max(0.02, custom)))
        }
    }

    func restore() {
        if let original {
            UIScreen.main.brightness = original
            self.original = nil
        }
    }
}

// MARK: - 电量与时间

@MainActor
final class DeviceStatusMonitor: ObservableObject {
    @Published var batteryLevel: Double = 1
    @Published var isCharging: Bool = false
    @Published var clock: String = ""

    private var timer: Timer?

    init() {
        UIDevice.current.isBatteryMonitoringEnabled = true
        refresh()
        timer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refresh() }
        }
    }

    deinit { timer?.invalidate() }

    func refresh() {
        let level = UIDevice.current.batteryLevel
        batteryLevel = level < 0 ? 1 : Double(level)
        let state = UIDevice.current.batteryState
        isCharging = (state == .charging || state == .full)
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        clock = f.string(from: Date())
    }
}

// MARK: - 阅读计时

@MainActor
final class ReadingTimer: ObservableObject {
    @Published var elapsed: Int = 0
    /// 距离下一次护眼提醒的秒数
    @Published var restCountdown: Int = 0
    @Published var shouldRest = false

    private var timer: Timer?
    private var bookID: String = ""
    private var restInterval: Int = 0

    func start(bookID: String, restMinutes: Int) {
        stop()
        self.bookID = bookID
        self.restInterval = restMinutes * 60
        self.restCountdown = restInterval
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    private func tick() {
        elapsed += 1
        if elapsed % 30 == 0 {
            LibraryStore.shared.recordReading(seconds: 30, bookID: bookID)
        }
        if restInterval > 0 {
            restCountdown -= 1
            if restCountdown <= 0 {
                shouldRest = true
                restCountdown = restInterval
            }
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        let remainder = elapsed % 30
        if remainder > 0 { LibraryStore.shared.recordReading(seconds: remainder, bookID: bookID) }
        elapsed = 0
    }
}

// MARK: - 音量键翻页

/// 通过隐藏的 MPVolumeView + 监听系统音量变化实现音量键翻页。
/// 触发后把音量复位，避免真的把音量调没了。
@MainActor
final class VolumeKeyMonitor: ObservableObject {
    var onUp: () -> Void = {}
    var onDown: () -> Void = {}

    private var observation: NSKeyValueObservation?
    private var lastVolume: Float = 0
    private var enabled = false
    private var restoring = false
    /// 屏外的系统音量控件，用来吃掉音量 HUD
    private var hudSuppressor: MPVolumeView?

    func start() {
        guard !enabled else { return }
        enabled = true
        installHUDSuppressor()
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.ambient, options: [.mixWithOthers])
        try? session.setActive(true)
        lastVolume = session.outputVolume
        observation = session.observe(\.outputVolume, options: [.new]) { [weak self] _, change in
            guard let newValue = change.newValue else { return }
            Task { @MainActor in self?.handle(newValue) }
        }
    }

    private func handle(_ volume: Float) {
        guard enabled, !restoring else { restoring = false; return }
        if volume > lastVolume { onUp() } else if volume < lastVolume { onDown() }
        lastVolume = volume
    }

    private func installHUDSuppressor() {
        guard hudSuppressor == nil else { return }
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first(where: { $0.activationState == .foregroundActive })
        guard let window = scene?.windows.first(where: { $0.isKeyWindow }) ?? scene?.windows.first else { return }
        let v = MPVolumeView(frame: CGRect(x: -2000, y: -2000, width: 1, height: 1))
        v.alpha = 0.001
        v.isUserInteractionEnabled = false
        window.addSubview(v)
        hudSuppressor = v
    }

    func stop() {
        observation?.invalidate()
        observation = nil
        hudSuppressor?.removeFromSuperview()
        hudSuppressor = nil
        enabled = false
    }
}
