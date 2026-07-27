import SwiftUI
import UIKit

// MARK: - 颜色工具

extension UIColor {
    convenience init(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("#") { s.removeFirst() }
        var value: UInt64 = 0
        Scanner(string: s).scanHexInt64(&value)
        let r, g, b, a: CGFloat
        if s.count == 8 {
            r = CGFloat((value & 0xFF00_0000) >> 24) / 255
            g = CGFloat((value & 0x00FF_0000) >> 16) / 255
            b = CGFloat((value & 0x0000_FF00) >> 8) / 255
            a = CGFloat(value & 0x0000_00FF) / 255
        } else {
            r = CGFloat((value & 0xFF0000) >> 16) / 255
            g = CGFloat((value & 0x00FF00) >> 8) / 255
            b = CGFloat(value & 0x0000FF) / 255
            a = 1
        }
        self.init(red: r, green: g, blue: b, alpha: a)
    }

    var luminance: CGFloat {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        getRed(&r, green: &g, blue: &b, alpha: &a)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}

extension Color {
    init(hex: String) { self.init(uiColor: UIColor(hex: hex)) }
}

// MARK: - 阅读主题

struct ReadingTheme: Identifiable, Hashable {
    let id: String
    let name: String
    let background: String
    let text: String
    /// 页眉页脚等次要信息
    let secondary: String
    let isDark: Bool
    /// 是否叠加纸纹，让长时间阅读不至于「太平」
    let paper: Bool

    var backgroundColor: Color { Color(hex: background) }
    var textColor: Color { Color(hex: text) }
    var secondaryColor: Color { Color(hex: secondary) }
    var uiBackground: UIColor { UIColor(hex: background) }
    var uiText: UIColor { UIColor(hex: text) }
    var uiSecondary: UIColor { UIColor(hex: secondary) }

    var colorScheme: ColorScheme { isDark ? .dark : .light }

    static let all: [ReadingTheme] = [
        ReadingTheme(id: "paper",   name: "宣纸", background: "#F3EDE1", text: "#33302A", secondary: "#9A9386", isDark: false, paper: true),
        ReadingTheme(id: "white",   name: "净白", background: "#FCFCFA", text: "#2B2B2E", secondary: "#A6A6AA", isDark: false, paper: false),
        ReadingTheme(id: "kraft",   name: "牛皮", background: "#E8D9BE", text: "#40372A", secondary: "#9C8C71", isDark: false, paper: true),
        ReadingTheme(id: "green",   name: "护眼", background: "#CDE3CE", text: "#2C3A2E", secondary: "#7B9179", isDark: false, paper: false),
        ReadingTheme(id: "sky",     name: "天青", background: "#DCE7EC", text: "#2C363C", secondary: "#7D8E97", isDark: false, paper: false),
        ReadingTheme(id: "sakura",  name: "初樱", background: "#F4E3E4", text: "#3D3033", secondary: "#A98D91", isDark: false, paper: false),
        ReadingTheme(id: "night",   name: "夜阑", background: "#1B1C1E", text: "#B4B0AA", secondary: "#6A6862", isDark: true,  paper: false),
        ReadingTheme(id: "sepiaN",  name: "暖夜", background: "#221E1A", text: "#A99685", secondary: "#6E6459", isDark: true, paper: true),
        ReadingTheme(id: "oled",    name: "纯黑", background: "#000000", text: "#98958F", secondary: "#5A5854", isDark: true,  paper: false)
    ]

    static func theme(id: String) -> ReadingTheme {
        all.first(where: { $0.id == id }) ?? all[0]
    }
}

// MARK: - 应用主色

enum Brand {
    static let orange = Color(hex: "#FF6A2B")
    static let orangeDeep = Color(hex: "#F2531B")
    static let orangeSoft = Color(hex: "#FFF0E7")
    static let uiOrange = UIColor(hex: "#FF6A2B")

    static let gradient = LinearGradient(
        colors: [Color(hex: "#FF8A3D"), Color(hex: "#FF5A1F")],
        startPoint: .topLeading, endPoint: .bottomTrailing
    )
}
