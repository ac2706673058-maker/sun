import SwiftUI
import UIKit

struct PageRef: Hashable {
    var chapter: Int
    var page: Int
}

struct ChapterLayout {
    let chapterIndex: Int
    let attributed: NSAttributedString
    let bodyStart: Int
    let pages: [NSRange]

    var pageCount: Int { max(1, pages.count) }

    func range(page: Int) -> NSRange {
        guard pages.indices.contains(page) else {
            return pages.last ?? NSRange(location: 0, length: attributed.length)
        }
        return pages[page]
    }

    /// 某页第一个正文字符在章节内的偏移
    func contentOffset(page: Int) -> Int {
        max(0, range(page: page).location - bodyStart)
    }

    /// 章节内偏移落在第几页
    func page(forContentOffset offset: Int) -> Int {
        let target = bodyStart + max(0, offset)
        for (i, r) in pages.enumerated() where target < r.location + r.length {
            return i
        }
        return max(0, pages.count - 1)
    }
}

/// 阅读引擎：负责排版缓存、翻页导航、进度换算。
/// 章节按需排版并缓存，前后各预热一章，翻页时不会掉帧。
@MainActor
final class ReaderEngine: ObservableObject {

    let book: Book
    private let settings: ReaderSettings

    @Published private(set) var current: PageRef
    /// 排版参数或尺寸变化时自增，用于让分页容器整体重建
    @Published private(set) var layoutGeneration: Int = 0

    private(set) var contentSize: CGSize = .zero
    private var cache: [Int: ChapterLayout] = [:]
    private let chapterCharPrefix: [Int]
    private let totalChars: Int

    init(book: Book, settings: ReaderSettings, start: ReadingProgress) {
        self.book = book
        self.settings = settings
        var running = 0
        var prefix: [Int] = []
        for c in book.chapters {
            prefix.append(running)
            running += c.charCount
        }
        self.chapterCharPrefix = prefix
        self.totalChars = max(1, running)
        let chapter = min(max(0, start.chapterIndex), max(0, book.chapters.count - 1))
        self.current = PageRef(chapter: chapter, page: 0)
        self.pendingOffset = start.charOffset
    }

    /// 首次布局完成前先记住要恢复到的偏移
    private var pendingOffset: Int?

    // MARK: - 尺寸与失效

    func updateContentSize(_ size: CGSize) {
        guard size.width > 20, size.height > 20 else { return }
        let changed = abs(size.width - contentSize.width) > 0.5 || abs(size.height - contentSize.height) > 0.5
        guard changed else { return }
        let keepOffset = pendingOffset ?? currentContentOffset
        contentSize = size
        cache.removeAll()
        restore(chapter: current.chapter, offset: keepOffset)
        pendingOffset = nil
        layoutGeneration += 1
    }

    func invalidateTypography() {
        let keepOffset = currentContentOffset
        cache.removeAll()
        restore(chapter: current.chapter, offset: keepOffset)
        layoutGeneration += 1
    }

    private func restore(chapter: Int, offset: Int) {
        let l = layout(for: chapter)
        current = PageRef(chapter: chapter, page: l.page(forContentOffset: offset))
    }

    // MARK: - 排版缓存

    func layout(for chapterIndex: Int) -> ChapterLayout {
        if let cached = cache[chapterIndex] { return cached }
        let idx = min(max(0, chapterIndex), max(0, book.chapters.count - 1))
        let chapter = book.chapters[idx]
        let built = TextStyler.build(chapter: chapter, config: settings.config, theme: settings.effectiveTheme)
        let size = contentSize.width > 20 ? contentSize : CGSize(width: 320, height: 560)
        let pages = Paginator.paginate(built.attributed, size: size)
        let layout = ChapterLayout(chapterIndex: idx, attributed: built.attributed,
                                   bodyStart: built.bodyStart, pages: pages)
        cache[idx] = layout
        return layout
    }

    /// 预热相邻章节，避免翻到章节交界处时卡一下
    func warmNeighbors() {
        let next = current.chapter + 1
        let prev = current.chapter - 1
        Task { @MainActor in
            if book.chapters.indices.contains(next) { _ = self.layout(for: next) }
            if book.chapters.indices.contains(prev) { _ = self.layout(for: prev) }
            // 缓存太多章会吃内存，只保留当前附近 5 章
            let keep = Set((self.current.chapter - 2)...(self.current.chapter + 2))
            for key in self.cache.keys where !keep.contains(key) {
                self.cache.removeValue(forKey: key)
            }
        }
    }

    // MARK: - 导航

    var chapterCount: Int { book.chapters.count }

    func chapter(at index: Int) -> Chapter? {
        book.chapters.indices.contains(index) ? book.chapters[index] : nil
    }

    var currentChapter: Chapter? { chapter(at: current.chapter) }

    func pageCount(chapter: Int) -> Int { layout(for: chapter).pageCount }

    func range(for ref: PageRef) -> NSRange { layout(for: ref.chapter).range(page: ref.page) }

    func bodyStart(for ref: PageRef) -> Int { layout(for: ref.chapter).bodyStart }

    func attributed(for ref: PageRef) -> NSAttributedString { layout(for: ref.chapter).attributed }

    func contentOffset(of ref: PageRef) -> Int { layout(for: ref.chapter).contentOffset(page: ref.page) }

    var currentContentOffset: Int { contentOffset(of: current) }

    func next(after ref: PageRef) -> PageRef? {
        let l = layout(for: ref.chapter)
        if ref.page + 1 < l.pageCount { return PageRef(chapter: ref.chapter, page: ref.page + 1) }
        guard ref.chapter + 1 < chapterCount else { return nil }
        return PageRef(chapter: ref.chapter + 1, page: 0)
    }

    func previous(before ref: PageRef) -> PageRef? {
        if ref.page > 0 { return PageRef(chapter: ref.chapter, page: ref.page - 1) }
        guard ref.chapter > 0 else { return nil }
        let l = layout(for: ref.chapter - 1)
        return PageRef(chapter: ref.chapter - 1, page: l.pageCount - 1)
    }

    var isFirstPage: Bool { current.chapter == 0 && current.page == 0 }
    var isLastPage: Bool {
        current.chapter == chapterCount - 1 && current.page == pageCount(chapter: current.chapter) - 1
    }

    @discardableResult
    func goNext() -> Bool {
        guard let n = next(after: current) else { return false }
        current = n
        warmNeighbors()
        return true
    }

    @discardableResult
    func goPrevious() -> Bool {
        guard let p = previous(before: current) else { return false }
        current = p
        warmNeighbors()
        return true
    }

    func setCurrent(_ ref: PageRef) {
        guard ref != current else { return }
        current = ref
        warmNeighbors()
    }

    func jump(toChapter index: Int, offset: Int = 0) {
        let idx = min(max(0, index), chapterCount - 1)
        let l = layout(for: idx)
        current = PageRef(chapter: idx, page: l.page(forContentOffset: offset))
        warmNeighbors()
    }

    func jumpToNextChapter() { jump(toChapter: current.chapter + 1) }
    func jumpToPreviousChapter() { jump(toChapter: current.chapter - 1) }

    // MARK: - 进度

    /// 全书进度 0...1
    var bookProgress: Double {
        let base = chapterCharPrefix.indices.contains(current.chapter) ? chapterCharPrefix[current.chapter] : 0
        let inChapter = currentContentOffset
        return min(1, max(0, Double(base + inChapter) / Double(totalChars)))
    }

    /// 拖动全书进度条时定位
    func seek(bookProgress p: Double) {
        let target = Int(Double(totalChars) * min(1, max(0, p)))
        var chapterIndex = 0
        for (i, start) in chapterCharPrefix.enumerated() where start <= target { chapterIndex = i }
        let offset = target - (chapterCharPrefix.indices.contains(chapterIndex) ? chapterCharPrefix[chapterIndex] : 0)
        jump(toChapter: chapterIndex, offset: max(0, offset))
    }

    var chapterProgressText: String {
        let l = layout(for: current.chapter)
        return "\(current.page + 1)/\(l.pageCount)"
    }

    var remainingPagesInChapter: Int {
        max(0, layout(for: current.chapter).pageCount - current.page - 1)
    }

    var percentText: String { String(format: "%.1f%%", bookProgress * 100) }

    /// 按当前阅读速度估算读完本章还要多久（分钟）
    func minutesLeftInChapter(charsPerMinute: Double) -> Int {
        guard let chapter = currentChapter else { return 0 }
        let remaining = max(0, chapter.charCount - currentContentOffset)
        return max(1, Int(Double(remaining) / max(150, charsPerMinute)))
    }

    /// 当前页的正文文本（听书、复制整页时用）
    func pageText(for ref: PageRef) -> String {
        let l = layout(for: ref.chapter)
        let ns = l.attributed.string as NSString
        let r = l.range(page: ref.page)
        let loc = min(max(0, r.location), ns.length)
        let len = min(r.length, ns.length - loc)
        guard len > 0 else { return "" }
        return ns.substring(with: NSRange(location: loc, length: len))
    }
}
