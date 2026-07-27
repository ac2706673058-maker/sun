import SwiftUI
import UIKit

// MARK: - 翻页方式

enum PageAnimation: Int, Codable, CaseIterable, Identifiable {
    case cover = 0      // 覆盖：新页盖住旧页（跟手，默认）
    case curl = 1       // 仿真：书页卷曲
    case slide = 2      // 平移：整页横向滑动
    case none = 3       // 无动画：极速切换
    case scroll = 4     // 上下滚动：连续正文流

    var id: Int { rawValue }

    var name: String {
        switch self {
        case .cover: return "覆盖"
        case .curl: return "仿真"
        case .slide: return "平移"
        case .none: return "无动画"
        case .scroll: return "上下滚动"
        }
    }

    var icon: String {
        switch self {
        case .cover: return "rectangle.stack"
        case .curl: return "book.pages"
        case .slide: return "arrow.left.arrow.right"
        case .none: return "bolt"
        case .scroll: return "arrow.up.arrow.down"
        }
    }

    var isVerticalScroll: Bool { self == .scroll }
}

// MARK: - 正文字体

enum ReaderFont: Int, Codable, CaseIterable, Identifiable {
    case pingfang = 0
    case songti = 1
    case kaiti = 2
    case yuanti = 3
    case heiti = 4

    var id: Int { rawValue }

    var name: String {
        switch self {
        case .pingfang: return "苹方"
        case .songti: return "宋体"
        case .kaiti: return "楷体"
        case .yuanti: return "圆体"
        case .heiti: return "黑体"
        }
    }

    /// 名称候选，逐个尝试，全部不可用时回退系统字体
    var candidates: [String] {
        switch self {
        case .pingfang: return ["PingFangSC-Regular"]
        case .songti: return ["STSongti-SC-Regular", "Songti SC"]
        case .kaiti: return ["STKaiti-SC-Regular", "Kaiti SC", "STKaiti"]
        case .yuanti: return ["STYuanti-SC-Regular", "Yuanti SC"]
        case .heiti: return ["PingFangSC-Medium", "STHeitiSC-Medium"]
        }
    }

    func uiFont(size: CGFloat, bold: Bool) -> UIFont {
        for name in candidates {
            if var font = UIFont(name: name, size: size) {
                if bold, let d = font.fontDescriptor.withSymbolicTraits(.traitBold) {
                    font = UIFont(descriptor: d, size: size)
                }
                return font
            }
        }
        return UIFont.systemFont(ofSize: size, weight: bold ? .semibold : .regular)
    }
}

// MARK: - 点击翻页热区

enum TapZoneStyle: Int, Codable, CaseIterable, Identifiable {
    case leftPrevRightNext = 0   // 左上一页 / 右下一页（中间唤出菜单）
    case anywhereNext = 1        // 全屏下一页（中间小区域唤出菜单）
    case menuOnly = 2            // 只用手势翻页，点击一律唤出菜单

    var id: Int { rawValue }
    var name: String {
        switch self {
        case .leftPrevRightNext: return "左右翻页"
        case .anywhereNext: return "全屏下一页"
        case .menuOnly: return "仅手势翻页"
        }
    }
}

// MARK: - 自动阅读方式

enum AutoReadMode: Int, Codable, CaseIterable, Identifiable {
    case pageByPage = 0
    case smoothScroll = 1

    var id: Int { rawValue }
    var name: String { self == .pageByPage ? "定时翻页" : "平滑滚动" }
}

// MARK: - 配置

struct ReaderConfig: Codable, Equatable {
    // 排版
    var fontSize: CGFloat = 19
    var fontFamily: ReaderFont = .pingfang
    var boldText: Bool = false
    var lineHeightMultiple: CGFloat = 1.62
    var paragraphSpacing: CGFloat = 13
    var letterSpacing: CGFloat = 0.2
    var horizontalMargin: CGFloat = 22
    var topMargin: CGFloat = 12
    var bottomMargin: CGFloat = 10
    var indentFirstLine: Bool = true
    var justified: Bool = true
    var traditionalChinese: Bool = false

    // 外观
    var themeID: String = "paper"
    var followSystemDarkTheme: Bool = true      // 系统深色时自动切到夜间主题
    var nightThemeID: String = "night"
    var customBrightness: Double = 0.6
    var followSystemBrightness: Bool = true
    var warmFilter: Double = 0                   // 0...1 夜间暖光滤镜
    var pageAnimation: PageAnimation = .cover

    // 交互
    var tapZone: TapZoneStyle = .leftPrevRightNext
    var volumeKeyTurnPage: Bool = false
    var hapticFeedback: Bool = true
    var keepScreenOn: Bool = true
    var fullScreenMode: Bool = true              // 隐藏系统状态栏，用自绘信息栏
    var showHeader: Bool = true
    var showFooter: Bool = true
    var showBattery: Bool = true
    var showClock: Bool = true
    var showPagePercent: Bool = true
    var showChapterPageCount: Bool = true        // 「本章 3/12 页」

    // 自动阅读 / 听书
    var autoReadMode: AutoReadMode = .pageByPage
    var autoReadSpeed: Double = 0.5              // 0...1
    var speechRate: Double = 0.5                 // 0...1
    var speechVoiceID: String = ""
    var speechPitch: Double = 1.0

    // 关怀
    var eyeRestMinutes: Int = 0                  // 0 = 关闭
    var dailyGoalMinutes: Int = 30

    var theme: ReadingTheme { ReadingTheme.theme(id: themeID) }
}

@MainActor
final class ReaderSettings: ObservableObject {
    static let shared = ReaderSettings()

    @Published var config: ReaderConfig {
        didSet { if config != oldValue { persist() } }
    }

    /// 系统深色模式状态由 RootView 注入，用于「跟随系统夜间主题」
    @Published var systemIsDark: Bool = false

    private let key = "reader.config.v1"

    private init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let decoded = try? JSONDecoder().decode(ReaderConfig.self, from: data) {
            config = decoded
        } else {
            config = ReaderConfig()
        }
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(config) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    /// 实际生效的主题（考虑「跟随系统深色」）
    var effectiveTheme: ReadingTheme {
        if config.followSystemDarkTheme && systemIsDark && !config.theme.isDark {
            return ReadingTheme.theme(id: config.nightThemeID)
        }
        return config.theme
    }

    // 便捷步进
    func bumpFontSize(_ delta: CGFloat) {
        config.fontSize = min(30, max(13, config.fontSize + delta))
    }

    func resetTypography() {
        config.fontSize = 19
        config.lineHeightMultiple = 1.62
        config.paragraphSpacing = 13
        config.letterSpacing = 0.2
        config.horizontalMargin = 22
        config.boldText = false
    }
}
