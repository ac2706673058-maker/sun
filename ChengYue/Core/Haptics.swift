import UIKit

/// 统一的触感反馈。翻页、吸附、进度刻度都会用到，
/// 生成器提前 prepare，避免第一次触发时的延迟。
@MainActor
enum Haptics {
    private static let light = UIImpactFeedbackGenerator(style: .light)
    private static let soft = UIImpactFeedbackGenerator(style: .soft)
    private static let rigid = UIImpactFeedbackGenerator(style: .rigid)
    private static let selection = UISelectionFeedbackGenerator()
    private static let notice = UINotificationFeedbackGenerator()

    static var enabled: Bool { ReaderSettings.shared.config.hapticFeedback }

    static func prepare() {
        light.prepare(); soft.prepare(); rigid.prepare(); selection.prepare()
    }

    static func pageTurn() {
        guard enabled else { return }
        soft.impactOccurred(intensity: 0.55)
        soft.prepare()
    }

    static func tick() {
        guard enabled else { return }
        selection.selectionChanged()
        selection.prepare()
    }

    static func boundary() {
        guard enabled else { return }
        rigid.impactOccurred(intensity: 0.75)
        rigid.prepare()
    }

    static func tap() {
        guard enabled else { return }
        light.impactOccurred(intensity: 0.5)
        light.prepare()
    }

    static func success() {
        guard enabled else { return }
        notice.notificationOccurred(.success)
    }
}
