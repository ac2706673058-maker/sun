import Foundation

// MARK: - 书籍

struct Chapter: Identifiable, Codable, Hashable {
    var id: String
    var index: Int
    var title: String
    var content: String

    var charCount: Int { content.count }
}

struct Book: Identifiable, Codable, Hashable {
    var id: String
    var title: String
    var author: String
    var intro: String
    var category: String
    var tags: [String]
    var isFinished: Bool
    var rating: Double
    var readerCount: Int
    /// 封面渐变色（十六进制），用于生成矢量封面，不依赖图片资源
    var coverColors: [String]
    /// 封面装饰字，取书名首字或象形字
    var coverGlyph: String
    var chapters: [Chapter]

    var wordCount: Int { chapters.reduce(0) { $0 + $1.charCount } }

    var wordCountText: String {
        let n = wordCount
        if n >= 10_000 { return String(format: "%.1f万字", Double(n) / 10_000) }
        return "\(n)字"
    }

    var statusText: String { isFinished ? "已完结" : "连载中" }

    var popularityText: String {
        let n = readerCount
        if n >= 10_000 { return String(format: "%.1f万人在读", Double(n) / 10_000) }
        return "\(n)人在读"
    }
}

// MARK: - 阅读进度

struct ReadingProgress: Codable, Hashable {
    var bookID: String
    var chapterIndex: Int
    /// 章节内字符偏移，保证换字号/换设备后仍能精确回到原句
    var charOffset: Int
    var percent: Double
    var updatedAt: Date

    static func initial(bookID: String) -> ReadingProgress {
        ReadingProgress(bookID: bookID, chapterIndex: 0, charOffset: 0, percent: 0, updatedAt: Date())
    }
}

// MARK: - 书架

struct ShelfItem: Identifiable, Codable, Hashable {
    var bookID: String
    var addedAt: Date
    var pinned: Bool
    var group: String?

    var id: String { bookID }
}

// MARK: - 书签 / 划线 / 想法

struct Bookmark: Identifiable, Codable, Hashable {
    var id: String = UUID().uuidString
    var bookID: String
    var chapterIndex: Int
    var chapterTitle: String
    var charOffset: Int
    var excerpt: String
    var createdAt: Date = Date()
}

enum HighlightStyle: Int, Codable, CaseIterable, Identifiable {
    case background = 0
    case wavy = 1
    case underline = 2

    var id: Int { rawValue }
    var name: String {
        switch self {
        case .background: return "背景"
        case .wavy: return "波浪线"
        case .underline: return "直线"
        }
    }
}

struct Highlight: Identifiable, Codable, Hashable {
    var id: String = UUID().uuidString
    var bookID: String
    var chapterIndex: Int
    var location: Int
    var length: Int
    var text: String
    var note: String = ""
    var colorIndex: Int = 0
    var style: HighlightStyle = .background
    var createdAt: Date = Date()

    var range: NSRange { NSRange(location: location, length: length) }
    var hasNote: Bool { !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    /// 划线可选颜色（十六进制，绘制时会按主题自动调整透明度）
    static let palette = ["#FFD98E", "#A8E6CF", "#BFD7FF", "#FFC7C7", "#E0C3FF"]
}

// MARK: - 阅读时长统计

struct ReadingSession: Codable, Hashable {
    var day: String          // yyyy-MM-dd
    var bookID: String
    var seconds: Int
}

// MARK: - 浏览历史

struct HistoryEntry: Identifiable, Codable, Hashable {
    var bookID: String
    var lastOpened: Date
    var id: String { bookID }
}
