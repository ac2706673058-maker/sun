import Foundation
import SwiftUI

/// 本地书库 + 用户数据（书架、进度、书签、划线、时长）。
/// 目前全部来自内置样例书，未接入任何书源；换成网络书源时只需替换 `books` 的来源。
@MainActor
final class LibraryStore: ObservableObject {
    static let shared = LibraryStore()

    @Published private(set) var books: [Book]

    @Published var shelf: [ShelfItem] = [] { didSet { scheduleSave() } }
    @Published var progress: [String: ReadingProgress] = [:] { didSet { scheduleSave() } }
    @Published var bookmarks: [Bookmark] = [] { didSet { scheduleSave() } }
    @Published var highlights: [Highlight] = [] { didSet { scheduleSave() } }
    @Published var sessions: [ReadingSession] = [] { didSet { scheduleSave() } }
    @Published var history: [HistoryEntry] = [] { didSet { scheduleSave() } }

    private var saveTask: Task<Void, Never>?
    private var loading = true

    private struct Persisted: Codable {
        var shelf: [ShelfItem]
        var progress: [String: ReadingProgress]
        var bookmarks: [Bookmark]
        var highlights: [Highlight]
        var sessions: [ReadingSession]
        var history: [HistoryEntry]
    }

    private var fileURL: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent("library.json")
    }

    private init() {
        books = SampleLibrary.books
        load()
        loading = false
    }

    // MARK: - 读写

    private func load() {
        guard let data = try? Data(contentsOf: fileURL),
              let decoded = try? JSONDecoder().decode(Persisted.self, from: data) else {
            seedShelf()
            return
        }
        shelf = decoded.shelf
        progress = decoded.progress
        bookmarks = decoded.bookmarks
        highlights = decoded.highlights
        sessions = decoded.sessions
        history = decoded.history
    }

    private func seedShelf() {
        // 首次启动放三本书在书架上，避免空白首屏
        let seeds = books.prefix(3)
        shelf = seeds.enumerated().map {
            ShelfItem(bookID: $0.element.id,
                      addedAt: Date().addingTimeInterval(Double(-$0.offset) * 3600),
                      pinned: false, group: nil)
        }
    }

    private func scheduleSave() {
        guard !loading else { return }
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard !Task.isCancelled else { return }
            await self?.saveNow()
        }
    }

    func saveNow() {
        let payload = Persisted(shelf: shelf, progress: progress, bookmarks: bookmarks,
                                highlights: highlights, sessions: sessions, history: history)
        guard let data = try? JSONEncoder().encode(payload) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }

    // MARK: - 书籍

    func book(id: String) -> Book? { books.first(where: { $0.id == id }) }

    func filtered(category: String) -> [Book] {
        category == "全部" ? books : books.filter { $0.category == category }
    }

    func search(_ text: String) -> [Book] {
        let q = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return [] }
        return books.filter {
            $0.title.localizedCaseInsensitiveContains(q)
            || $0.author.localizedCaseInsensitiveContains(q)
            || $0.intro.localizedCaseInsensitiveContains(q)
            || $0.tags.contains(where: { $0.localizedCaseInsensitiveContains(q) })
        }
    }

    // MARK: - 书架

    var shelfBooks: [Book] {
        shelf.sorted { a, b in
            if a.pinned != b.pinned { return a.pinned }
            let ta = progress[a.bookID]?.updatedAt ?? a.addedAt
            let tb = progress[b.bookID]?.updatedAt ?? b.addedAt
            return ta > tb
        }.compactMap { book(id: $0.bookID) }
    }

    func isOnShelf(_ id: String) -> Bool { shelf.contains(where: { $0.bookID == id }) }

    func addToShelf(_ id: String) {
        guard !isOnShelf(id) else { return }
        shelf.append(ShelfItem(bookID: id, addedAt: Date(), pinned: false, group: nil))
    }

    func removeFromShelf(_ ids: Set<String>) {
        shelf.removeAll { ids.contains($0.bookID) }
    }

    func togglePin(_ id: String) {
        guard let idx = shelf.firstIndex(where: { $0.bookID == id }) else { return }
        shelf[idx].pinned.toggle()
    }

    var lastReadBook: Book? {
        guard let latest = progress.values.max(by: { $0.updatedAt < $1.updatedAt }) else {
            return shelfBooks.first
        }
        return book(id: latest.bookID)
    }

    // MARK: - 进度

    func readingProgress(for bookID: String) -> ReadingProgress {
        progress[bookID] ?? .initial(bookID: bookID)
    }

    func updateProgress(bookID: String, chapterIndex: Int, charOffset: Int, percent: Double) {
        progress[bookID] = ReadingProgress(bookID: bookID, chapterIndex: chapterIndex,
                                           charOffset: charOffset, percent: percent,
                                           updatedAt: Date())
    }

    func touchHistory(_ bookID: String) {
        history.removeAll { $0.bookID == bookID }
        history.insert(HistoryEntry(bookID: bookID, lastOpened: Date()), at: 0)
        if history.count > 60 { history.removeLast(history.count - 60) }
    }

    // MARK: - 书签

    func bookmarkList(bookID: String) -> [Bookmark] {
        bookmarks.filter { $0.bookID == bookID }.sorted {
            ($0.chapterIndex, $0.charOffset) < ($1.chapterIndex, $1.charOffset)
        }
    }

    func bookmark(bookID: String, chapter: Int, range: NSRange) -> Bookmark? {
        bookmarks.first {
            $0.bookID == bookID && $0.chapterIndex == chapter
            && $0.charOffset >= range.location && $0.charOffset < range.location + max(range.length, 1)
        }
    }

    func addBookmark(_ mark: Bookmark) { bookmarks.append(mark) }
    func removeBookmark(id: String) { bookmarks.removeAll { $0.id == id } }

    // MARK: - 划线与想法

    func highlightList(bookID: String) -> [Highlight] {
        highlights.filter { $0.bookID == bookID }.sorted {
            ($0.chapterIndex, $0.location) < ($1.chapterIndex, $1.location)
        }
    }

    func highlightList(bookID: String, chapter: Int) -> [Highlight] {
        highlights.filter { $0.bookID == bookID && $0.chapterIndex == chapter }
    }

    func addHighlight(_ h: Highlight) {
        // 与已有划线重叠时合并，避免层层叠加导致颜色越来越脏
        var merged = h
        let overlapping = highlights.filter {
            $0.bookID == h.bookID && $0.chapterIndex == h.chapterIndex
            && NSIntersectionRange($0.range, h.range).length > 0
        }
        if !overlapping.isEmpty {
            let lo = min(h.location, overlapping.map(\.location).min() ?? h.location)
            let hi = max(h.location + h.length,
                         overlapping.map { $0.location + $0.length }.max() ?? h.location + h.length)
            merged.location = lo
            merged.length = hi - lo
            let notes = overlapping.map(\.note).filter { !$0.isEmpty }
            if merged.note.isEmpty { merged.note = notes.joined(separator: "\n") }
            highlights.removeAll { o in overlapping.contains(where: { $0.id == o.id }) }
        }
        highlights.append(merged)
    }

    func updateHighlight(_ h: Highlight) {
        guard let idx = highlights.firstIndex(where: { $0.id == h.id }) else { return }
        highlights[idx] = h
    }

    func removeHighlight(id: String) { highlights.removeAll { $0.id == id } }

    // MARK: - 阅读时长

    static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    func recordReading(seconds: Int, bookID: String) {
        guard seconds > 0 else { return }
        let day = Self.dayFormatter.string(from: Date())
        if let idx = sessions.firstIndex(where: { $0.day == day && $0.bookID == bookID }) {
            sessions[idx].seconds += seconds
        } else {
            sessions.append(ReadingSession(day: day, bookID: bookID, seconds: seconds))
        }
    }

    var todaySeconds: Int {
        let day = Self.dayFormatter.string(from: Date())
        return sessions.filter { $0.day == day }.reduce(0) { $0 + $1.seconds }
    }

    var totalSeconds: Int { sessions.reduce(0) { $0 + $1.seconds } }

    /// 最近 7 天（含今天）每日分钟数，index 0 是 6 天前
    var weekMinutes: [Int] {
        (0..<7).reversed().map { offset in
            let date = Calendar.current.date(byAdding: .day, value: -offset, to: Date()) ?? Date()
            let key = Self.dayFormatter.string(from: date)
            return sessions.filter { $0.day == key }.reduce(0) { $0 + $1.seconds } / 60
        }
    }

    var streakDays: Int {
        var streak = 0
        var date = Date()
        while true {
            let key = Self.dayFormatter.string(from: date)
            let total = sessions.filter { $0.day == key }.reduce(0) { $0 + $1.seconds }
            if total >= 60 {
                streak += 1
            } else if streak > 0 || key != Self.dayFormatter.string(from: Date()) {
                break
            } else {
                break
            }
            guard let prev = Calendar.current.date(byAdding: .day, value: -1, to: date) else { break }
            date = prev
        }
        return streak
    }
}
