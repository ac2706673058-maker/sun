import SwiftUI
import UIKit

struct SelectionPayload {
    var chapter: Int
    var range: NSRange       // 章节内偏移
    var text: String
    var anchor: CGRect       // 窗口坐标，用于定位气泡菜单
}

/// 分页容器：覆盖 / 仿真 / 平移 / 无动画 / 上下滚动。
/// 覆盖、平移、无动画由自绘转场实现（跟手、可中途反悔）；
/// 仿真翻页复用 UIPageViewController 的 pageCurl；滚动模式用 UIScrollView 连续排版。
struct ReaderPager: UIViewControllerRepresentable {
    @ObservedObject var engine: ReaderEngine
    @ObservedObject var settings: ReaderSettings
    var highlights: [Highlight]
    var speakingRange: NSRange?
    var contentInsets: UIEdgeInsets
    var onTapMenu: () -> Void
    var onSelection: (SelectionPayload?) -> Void
    var onPageChanged: () -> Void
    var onBoundary: (Bool) -> Void       // true = 已到最后一页
    var controllerRef: (ReaderPagerController) -> Void

    func makeUIViewController(context: Context) -> ReaderPagerController {
        let vc = ReaderPagerController(engine: engine, settings: settings)
        vc.onTapMenu = onTapMenu
        vc.onSelection = onSelection
        vc.onPageChanged = onPageChanged
        vc.onBoundary = onBoundary
        controllerRef(vc)
        return vc
    }

    func updateUIViewController(_ vc: ReaderPagerController, context: Context) {
        vc.onTapMenu = onTapMenu
        vc.onSelection = onSelection
        vc.onPageChanged = onPageChanged
        vc.onBoundary = onBoundary
        vc.apply(highlights: highlights, speakingRange: speakingRange, insets: contentInsets)
    }
}

@MainActor
final class ReaderPagerController: UIViewController {

    let engine: ReaderEngine
    let settings: ReaderSettings

    var onTapMenu: () -> Void = {}
    var onSelection: (SelectionPayload?) -> Void = { _ in }
    var onPageChanged: () -> Void = {}
    var onBoundary: (Bool) -> Void = { _ in }

    private var highlights: [Highlight] = []
    private var speakingRange: NSRange?
    private var insets: UIEdgeInsets = .zero

    private var mode: PageAnimation = .cover
    private var generation: Int = -1

    // 自绘转场
    private var pageA = PageTextView()          // 当前页
    private var pageB = PageTextView()          // 目标页
    private var direction: Int = 0              // 1 = 下一页, -1 = 上一页
    private var incomingRef: PageRef?
    private var isAnimating = false

    // 仿真翻页
    private var curlController: UIPageViewController?

    // 滚动模式
    private var scrollView: UIScrollView?
    private var scrollPage = PageTextView()
    private var scrollChapter: Int = -1

    private lazy var panGesture: UIPanGestureRecognizer = {
        let g = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        g.delegate = self
        return g
    }()

    private lazy var tapGesture: UITapGestureRecognizer = {
        let g = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        g.delegate = self
        return g
    }()

    init(engine: ReaderEngine, settings: ReaderSettings) {
        self.engine = engine
        self.settings = settings
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        view.clipsToBounds = true
        [pageA, pageB].forEach { $0.delegate = self }
        scrollPage.delegate = self
        view.addGestureRecognizer(panGesture)
        view.addGestureRecognizer(tapGesture)
        rebuild(force: true)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let rect = contentRect
        engine.updateContentSize(rect.size)
        if mode == .scroll {
            scrollView?.frame = view.bounds
            layoutScrollContent()
        } else if !isAnimating {
            pageA.frame = rect
            pageB.frame = rect
            curlController?.view.frame = view.bounds
        }
        if generation != engine.layoutGeneration {
            rebuild(force: true)
        }
    }

    private var contentRect: CGRect {
        view.bounds.inset(by: insets)
    }

    // MARK: - 外部驱动

    func apply(highlights: [Highlight], speakingRange: NSRange?, insets: UIEdgeInsets) {
        let insetsChanged = self.insets != insets
        self.highlights = highlights
        self.speakingRange = speakingRange
        self.insets = insets
        if insetsChanged { view.setNeedsLayout() }
        if mode != settings.config.pageAnimation || generation != engine.layoutGeneration {
            rebuild(force: true)
        } else {
            refreshVisiblePages()
        }
    }

    func refreshVisiblePages() {
        if mode == .scroll {
            configureScrollPage()
        } else if mode == .curl {
            (curlController?.viewControllers?.first as? CurlPageController)?.apply(
                engine: engine, theme: settings.effectiveTheme,
                highlights: chapterHighlights(engine.current.chapter), speaking: speakingRange
            )
        } else {
            configure(pageA, ref: engine.current)
        }
    }

    private func chapterHighlights(_ chapter: Int) -> [Highlight] {
        highlights.filter { $0.chapterIndex == chapter }
    }

    private func configure(_ pv: PageTextView, ref: PageRef) {
        let layout = engine.layout(for: ref.chapter)
        pv.attributed = layout.attributed
        pv.bodyStart = layout.bodyStart
        pv.pageRange = layout.range(page: ref.page)
        pv.theme = settings.effectiveTheme
        pv.highlights = chapterHighlights(ref.chapter)
        pv.speakingRange = (ref == engine.current) ? speakingRange : nil
        pv.tag = ref.chapter
    }

    // MARK: - 构建 / 切换模式

    private func rebuild(force: Bool) {
        let newMode = settings.config.pageAnimation
        let modeChanged = newMode != mode
        mode = newMode
        generation = engine.layoutGeneration

        if modeChanged || force {
            teardownAll()
            switch mode {
            case .curl: setupCurl()
            case .scroll: setupScroll()
            default: setupCustom()
            }
        } else {
            refreshVisiblePages()
        }
    }

    private func teardownAll() {
        pageA.removeFromSuperview()
        pageB.removeFromSuperview()
        scrollView?.removeFromSuperview()
        scrollView = nil
        scrollChapter = -1
        if let curl = curlController {
            curl.willMove(toParent: nil)
            curl.view.removeFromSuperview()
            curl.removeFromParent()
            curlController = nil
        }
        isAnimating = false
        direction = 0
        incomingRef = nil
    }

    private func setupCustom() {
        pageA.frame = contentRect
        pageB.frame = contentRect
        pageB.isHidden = true
        view.addSubview(pageA)
        view.addSubview(pageB)
        configure(pageA, ref: engine.current)
        panGesture.isEnabled = (mode != .none)
    }

    private func setupCurl() {
        let curl = UIPageViewController(transitionStyle: .pageCurl,
                                        navigationOrientation: .horizontal,
                                        options: [UIPageViewController.OptionsKey.spineLocation: UIPageViewController.SpineLocation.min.rawValue])
        curl.dataSource = self
        curl.delegate = self
        curl.isDoubleSided = false
        curl.view.frame = view.bounds
        curl.view.backgroundColor = .clear
        addChild(curl)
        view.addSubview(curl.view)
        curl.didMove(toParent: self)
        curl.setViewControllers([makeCurlPage(engine.current)], direction: .forward, animated: false)
        curlController = curl
        panGesture.isEnabled = false
        // 让 UIPageViewController 自己的手势工作，但保留我们的点击热区
        for gr in curl.gestureRecognizers {
            gr.require(toFail: tapGesture)
        }
    }

    private func makeCurlPage(_ ref: PageRef) -> CurlPageController {
        let vc = CurlPageController(ref: ref, insets: insets,
                                    background: settings.effectiveTheme.uiBackground)
        vc.pageView.delegate = self
        vc.apply(engine: engine, theme: settings.effectiveTheme,
                 highlights: chapterHighlights(ref.chapter),
                 speaking: ref == engine.current ? speakingRange : nil)
        return vc
    }

    private func setupScroll() {
        let sv = UIScrollView(frame: view.bounds)
        sv.backgroundColor = .clear
        sv.showsVerticalScrollIndicator = false
        sv.alwaysBounceVertical = true
        sv.delegate = self
        sv.contentInsetAdjustmentBehavior = .never
        sv.addSubview(scrollPage)
        view.addSubview(sv)
        scrollView = sv
        panGesture.isEnabled = false
        configureScrollPage()
    }

    private func configureScrollPage() {
        guard let sv = scrollView else { return }
        let layout = engine.layout(for: engine.current.chapter)
        let width = view.bounds.width - insets.left - insets.right
        guard width > 20 else { return }
        let rebuilt = scrollChapter != engine.current.chapter
        scrollPage.attributed = layout.attributed
        scrollPage.bodyStart = layout.bodyStart
        scrollPage.pageRange = NSRange(location: 0, length: layout.attributed.length)
        scrollPage.theme = settings.effectiveTheme
        scrollPage.highlights = chapterHighlights(engine.current.chapter)
        scrollPage.speakingRange = speakingRange
        scrollPage.tag = engine.current.chapter
        let height = Paginator.height(layout.attributed, width: width)
        scrollPage.frame = CGRect(x: insets.left, y: insets.top, width: width, height: height)
        sv.contentSize = CGSize(width: view.bounds.width, height: height + insets.top + insets.bottom + 40)
        if rebuilt {
            scrollChapter = engine.current.chapter
            let targetOffsetChar = engine.currentContentOffset
            sv.setContentOffset(CGPoint(x: 0, y: yOffset(forContentChar: targetOffsetChar)), animated: false)
        }
    }

    private func layoutScrollContent() { configureScrollPage() }

    private func yOffset(forContentChar char: Int) -> CGFloat {
        guard char > 0 else { return 0 }
        let layout = engine.layout(for: engine.current.chapter)
        let target = layout.bodyStart + char
        // 二分找到该字符在长页中的纵坐标
        var low: CGFloat = 0
        var high = scrollPage.bounds.height
        for _ in 0..<24 {
            let mid = (low + high) / 2
            guard let idx = scrollPage.characterIndex(at: CGPoint(x: 4, y: mid)) else { break }
            if idx < target { low = mid } else { high = mid }
        }
        return max(0, low - 8)
    }

    // MARK: - 翻页

    func turn(next: Bool, animated: Bool = true) {
        guard !isAnimating else { return }
        if mode == .scroll {
            guard let sv = scrollView else { return }
            let delta = (view.bounds.height - insets.top - insets.bottom) * 0.9
            let target = sv.contentOffset.y + (next ? delta : -delta)
            let maxY = max(0, sv.contentSize.height - sv.bounds.height)
            sv.setContentOffset(CGPoint(x: 0, y: min(maxY, max(0, target))), animated: animated)
            return
        }
        if mode == .curl {
            guard let curl = curlController,
                  let target = next ? engine.next(after: engine.current) : engine.previous(before: engine.current) else {
                onBoundary(next); Haptics.boundary(); return
            }
            curl.setViewControllers([makeCurlPage(target)],
                                    direction: next ? .forward : .reverse,
                                    animated: animated) { [weak self] done in
                guard done, let self else { return }
                self.engine.setCurrent(target)
                Haptics.pageTurn()
                self.onPageChanged()
            }
            return
        }

        guard let target = next ? engine.next(after: engine.current) : engine.previous(before: engine.current) else {
            onBoundary(next)
            Haptics.boundary()
            bounce(next: next)
            return
        }

        if mode == .none || !animated {
            engine.setCurrent(target)
            configure(pageA, ref: target)
            Haptics.pageTurn()
            onPageChanged()
            return
        }

        beginTransition(next: next, target: target)
        isAnimating = true
        UIView.animate(withDuration: 0.3, delay: 0, options: [.curveEaseOut, .allowUserInteraction]) {
            self.applyTransition(progress: 1)
        } completion: { _ in
            self.finishTransition(commit: true)
        }
    }

    /// 自动阅读的平滑滚动
    func autoScrollStep(_ dy: CGFloat) {
        guard mode == .scroll, let sv = scrollView else { return }
        let maxY = max(0, sv.contentSize.height - sv.bounds.height)
        let target = sv.contentOffset.y + dy
        if target >= maxY {
            sv.contentOffset = CGPoint(x: 0, y: maxY)
            scrollViewDidScroll(sv)
        } else {
            sv.contentOffset = CGPoint(x: 0, y: target)
        }
    }

    /// 让页面滚动/跳转到引擎当前位置（目录跳转、进度条拖动后调用）
    func syncToEngine(animated: Bool) {
        switch mode {
        case .scroll:
            configureScrollPage()
            scrollView?.setContentOffset(CGPoint(x: 0, y: 0), animated: animated)
        case .curl:
            curlController?.setViewControllers([makeCurlPage(engine.current)],
                                               direction: .forward, animated: false)
        default:
            configure(pageA, ref: engine.current)
            pageA.transform = .identity
            pageB.isHidden = true
        }
    }

    private func bounce(next: Bool) {
        let dx: CGFloat = next ? -26 : 26
        UIView.animate(withDuration: 0.14, animations: {
            self.pageA.transform = CGAffineTransform(translationX: dx, y: 0)
        }, completion: { _ in
            UIView.animate(withDuration: 0.26, delay: 0,
                           usingSpringWithDamping: 0.62, initialSpringVelocity: 0.4,
                           options: [.curveEaseOut]) {
                self.pageA.transform = .identity
            }
        })
    }

    private func beginTransition(next: Bool, target: PageRef) {
        direction = next ? 1 : -1
        incomingRef = target
        configure(pageB, ref: target)
        pageB.isHidden = false
        pageB.frame = contentRect
        pageA.frame = contentRect

        if mode == .cover {
            if next {
                view.bringSubviewToFront(pageB)
                applyShadow(to: pageB)
                pageB.transform = CGAffineTransform(translationX: view.bounds.width, y: 0)
                pageA.transform = .identity
            } else {
                view.bringSubviewToFront(pageA)
                applyShadow(to: pageA)
                pageB.transform = .identity
                pageA.transform = .identity
            }
        } else { // slide
            clearShadow(pageA); clearShadow(pageB)
            if next {
                pageB.transform = CGAffineTransform(translationX: view.bounds.width, y: 0)
                pageA.transform = .identity
            } else {
                pageB.transform = CGAffineTransform(translationX: -view.bounds.width, y: 0)
                pageA.transform = .identity
            }
        }
    }

    /// progress: 0 = 未翻动, 1 = 翻页完成
    private func applyTransition(progress: CGFloat) {
        let w = view.bounds.width
        let p = min(1, max(0, progress))
        if mode == .cover {
            if direction > 0 {
                pageB.transform = CGAffineTransform(translationX: w * (1 - p), y: 0)
            } else {
                pageA.transform = CGAffineTransform(translationX: w * p, y: 0)
            }
        } else {
            if direction > 0 {
                pageA.transform = CGAffineTransform(translationX: -w * p, y: 0)
                pageB.transform = CGAffineTransform(translationX: w * (1 - p), y: 0)
            } else {
                pageA.transform = CGAffineTransform(translationX: w * p, y: 0)
                pageB.transform = CGAffineTransform(translationX: -w * (1 - p), y: 0)
            }
        }
    }

    private func finishTransition(commit: Bool) {
        isAnimating = false
        if commit, let target = incomingRef {
            engine.setCurrent(target)
            // pageB 成为新的当前页，交换引用，避免每次都重排
            let old = pageA
            pageA = pageB
            pageB = old
            Haptics.pageTurn()
            onPageChanged()
        }
        pageA.transform = .identity
        pageB.transform = .identity
        clearShadow(pageA); clearShadow(pageB)
        pageB.isHidden = true
        pageA.isHidden = false
        view.bringSubviewToFront(pageA)
        pageA.frame = contentRect
        pageB.frame = contentRect
        configure(pageA, ref: engine.current)
        incomingRef = nil
        direction = 0
    }

    private func applyShadow(to v: UIView) {
        v.layer.shadowColor = UIColor.black.cgColor
        v.layer.shadowOpacity = settings.effectiveTheme.isDark ? 0.5 : 0.22
        v.layer.shadowRadius = 8
        v.layer.shadowOffset = CGSize(width: direction > 0 ? -4 : 4, height: 0)
    }

    private func clearShadow(_ v: UIView) {
        v.layer.shadowOpacity = 0
    }

    // MARK: - 手势

    @objc private func handlePan(_ g: UIPanGestureRecognizer) {
        guard mode == .cover || mode == .slide else { return }
        let w = view.bounds.width
        let t = g.translation(in: view)
        switch g.state {
        case .began:
            if hasSelection { clearSelection(); return }
            direction = 0
        case .changed:
            if direction == 0 {
                guard abs(t.x) > 6 else { return }
                let next = t.x < 0
                guard let target = next ? engine.next(after: engine.current) : engine.previous(before: engine.current) else {
                    // 到头了，给一点阻尼提示
                    pageA.transform = CGAffineTransform(translationX: t.x * 0.22, y: 0)
                    return
                }
                beginTransition(next: next, target: target)
                g.setTranslation(.zero, in: view)
                return
            }
            let raw = direction > 0 ? -t.x : t.x
            applyTransition(progress: raw / w)
        case .ended, .cancelled:
            guard direction != 0 else {
                UIView.animate(withDuration: 0.25) { self.pageA.transform = .identity }
                return
            }
            let v = g.velocity(in: view)
            let raw = direction > 0 ? -t.x : t.x
            let vel = direction > 0 ? -v.x : v.x
            let progress = raw / w
            let commit = progress > 0.28 || vel > 620
            isAnimating = true
            let remaining = commit ? (1 - progress) : progress
            let duration = max(0.14, min(0.36, Double(remaining) * 0.42))
            UIView.animate(withDuration: duration, delay: 0,
                           options: [.curveEaseOut, .allowUserInteraction]) {
                self.applyTransition(progress: commit ? 1 : 0)
            } completion: { _ in
                self.finishTransition(commit: commit)
            }
        default:
            break
        }
    }

    @objc private func handleTap(_ g: UITapGestureRecognizer) {
        let point = g.location(in: view)
        if hasSelection {
            clearSelection()
            return
        }
        // 点在划线上：优先弹出该划线的操作
        let target: PageTextView = (mode == .scroll) ? scrollPage
            : ((mode == .curl) ? ((curlController?.viewControllers?.first as? CurlPageController)?.pageView ?? pageA) : pageA)
        let local = view.convert(point, to: target)
        if let h = target.highlight(at: local) {
            let spot = CGRect(x: point.x - 40, y: point.y - 12, width: 80, height: 24)
            onSelection(SelectionPayload(chapter: h.chapterIndex, range: h.range, text: h.text,
                                         anchor: view.convert(spot, to: nil)))
            return
        }

        let w = view.bounds.width
        let h = view.bounds.height
        let centerBox = CGRect(x: w * 0.3, y: h * 0.25, width: w * 0.4, height: h * 0.5)

        switch settings.config.tapZone {
        case .menuOnly:
            onTapMenu()
        case .anywhereNext:
            if centerBox.contains(point) { onTapMenu() } else { turn(next: true) }
        case .leftPrevRightNext:
            if centerBox.contains(point) {
                onTapMenu()
            } else if point.x < w * 0.3 {
                turn(next: false)
            } else {
                turn(next: true)
            }
        }
    }

    private var hasSelection: Bool {
        pageA.selectionRange != nil || pageB.selectionRange != nil || scrollPage.selectionRange != nil
            || (curlController?.viewControllers?.first as? CurlPageController)?.pageView.selectionRange != nil
    }

    func clearSelection() {
        pageA.clearSelection()
        pageB.clearSelection()
        scrollPage.clearSelection()
        (curlController?.viewControllers?.first as? CurlPageController)?.pageView.clearSelection()
        onSelection(nil)
    }
}

// MARK: - 手势代理

extension ReaderPagerController: UIGestureRecognizerDelegate {
    nonisolated func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                                       shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        false
    }

    nonisolated func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                                       shouldReceive touch: UITouch) -> Bool {
        true
    }
}

// MARK: - 文本选择回调

extension ReaderPagerController: PageTextViewDelegate {
    func pageTextView(_ view: PageTextView, selectionChanged range: NSRange?, anchor: CGRect) {
        emitSelection(from: view, anchor: anchor, ended: false)
    }

    func pageTextView(_ view: PageTextView, selectionEnded range: NSRange?, anchor: CGRect) {
        emitSelection(from: view, anchor: anchor, ended: true)
    }

    private func emitSelection(from view: PageTextView, anchor: CGRect, ended: Bool) {
        guard ended else { return }
        guard let content = view.selectedContent, content.text.count > 0 else {
            onSelection(nil)
            return
        }
        onSelection(SelectionPayload(chapter: view.tag, range: content.range,
                                     text: content.text, anchor: anchor))
    }
}

// MARK: - 仿真翻页数据源

extension ReaderPagerController: UIPageViewControllerDataSource, UIPageViewControllerDelegate {
    func pageViewController(_ pageViewController: UIPageViewController,
                            viewControllerBefore viewController: UIViewController) -> UIViewController? {
        guard let cur = viewController as? CurlPageController,
              let prev = engine.previous(before: cur.ref) else { return nil }
        return makeCurlPage(prev)
    }

    func pageViewController(_ pageViewController: UIPageViewController,
                            viewControllerAfter viewController: UIViewController) -> UIViewController? {
        guard let cur = viewController as? CurlPageController,
              let next = engine.next(after: cur.ref) else { return nil }
        return makeCurlPage(next)
    }

    func pageViewController(_ pageViewController: UIPageViewController,
                            didFinishAnimating finished: Bool,
                            previousViewControllers: [UIViewController],
                            transitionCompleted completed: Bool) {
        guard completed, let cur = pageViewController.viewControllers?.first as? CurlPageController else { return }
        engine.setCurrent(cur.ref)
        Haptics.pageTurn()
        onPageChanged()
    }
}

// MARK: - 滚动模式

extension ReaderPagerController: UIScrollViewDelegate {
    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        guard mode == .scroll else { return }
        let topY = scrollView.contentOffset.y - insets.top + 4
        let local = CGPoint(x: 6, y: max(0, topY))
        if let idx = scrollPage.characterIndex(at: local) {
            let layout = engine.layout(for: engine.current.chapter)
            let offset = max(0, idx - layout.bodyStart)
            let page = layout.page(forContentOffset: offset)
            if page != engine.current.page {
                engine.setCurrent(PageRef(chapter: engine.current.chapter, page: page))
                onPageChanged()
            }
        }
        // 滚到底自动进入下一章
        let bottom = scrollView.contentOffset.y + scrollView.bounds.height
        if bottom > scrollView.contentSize.height - 12 {
            if engine.current.chapter + 1 < engine.chapterCount, !isAnimating {
                isAnimating = true
                engine.jump(toChapter: engine.current.chapter + 1)
                configureScrollPage()
                scrollView.setContentOffset(.zero, animated: false)
                Haptics.tick()
                onPageChanged()
                isAnimating = false
            } else if engine.current.chapter + 1 >= engine.chapterCount {
                onBoundary(true)
            }
        }
    }
}

/// 仿真翻页里的单页控制器
@MainActor
final class CurlPageController: UIViewController {
    let ref: PageRef
    let pageView = PageTextView()
    private let insets: UIEdgeInsets
    private let background: UIColor

    init(ref: PageRef, insets: UIEdgeInsets, background: UIColor) {
        self.ref = ref
        self.insets = insets
        self.background = background
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        // 仿真翻页需要不透明页面，卷曲时背面才不会透出下层
        view.backgroundColor = background
        view.addSubview(pageView)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        pageView.frame = view.bounds.inset(by: insets)
    }

    func apply(engine: ReaderEngine, theme: ReadingTheme, highlights: [Highlight], speaking: NSRange?) {
        let layout = engine.layout(for: ref.chapter)
        pageView.attributed = layout.attributed
        pageView.bodyStart = layout.bodyStart
        pageView.pageRange = layout.range(page: ref.page)
        pageView.theme = theme
        pageView.highlights = highlights
        pageView.speakingRange = speaking
        pageView.tag = ref.chapter
    }
}
