import UIKit
import CoreText

@MainActor
protocol PageTextViewDelegate: AnyObject {
    /// 选区变化（拖动中持续回调）。anchor 为选区在窗口坐标系中的包围盒。
    func pageTextView(_ view: PageTextView, selectionChanged range: NSRange?, anchor: CGRect)
    /// 选区结束，可以弹菜单了
    func pageTextView(_ view: PageTextView, selectionEnded range: NSRange?, anchor: CGRect)
}

/// 单页正文渲染视图。
/// 分页与绘制共用同一个 framesetter 参数，保证「量」与「画」完全一致；
/// 同时负责划线绘制、听书跟读高亮、以及长按取词/拖拽选择。
final class PageTextView: UIView, UIGestureRecognizerDelegate {

    weak var delegate: PageTextViewDelegate?

    var attributed: NSAttributedString? { didSet { framesetter = nil; setNeedsDisplay() } }
    var pageRange = NSRange(location: 0, length: 0) { didSet { setNeedsDisplay() } }
    /// 正文在富文本中的起点，用于把「章节内偏移」换算成绘制坐标
    var bodyStart: Int = 0
    var theme: ReadingTheme = ReadingTheme.all[0] { didSet { setNeedsDisplay() } }
    var highlights: [Highlight] = [] { didSet { setNeedsDisplay() } }
    /// 听书正在朗读的句子（章节内偏移）
    var speakingRange: NSRange? { didSet { setNeedsDisplay() } }
    var selectionRange: NSRange? { didSet { setNeedsDisplay() } }
    var selectionEnabled = true

    private var framesetter: CTFramesetter?
    private var cachedFrame: CTFrame?
    private var cachedFrameSize: CGSize = .zero
    private var cachedFrameRange = NSRange(location: 0, length: 0)

    private var selectionAnchorIndex: Int?
    private enum DragTarget { case none, head, tail }
    private var dragTarget: DragTarget = .none

    private let handleRadius: CGFloat = 6

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        contentMode = .redraw
        setupGestures()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override var bounds: CGRect {
        didSet { if bounds.size != oldValue.size { cachedFrame = nil; setNeedsDisplay() } }
    }

    // MARK: - 绘制

    private func makeFrame() -> CTFrame? {
        guard let attributed, attributed.length > 0 else { return nil }
        let clamped = clampedPageRange(in: attributed.length)
        if let cachedFrame, cachedFrameSize == bounds.size,
           NSEqualRanges(cachedFrameRange, clamped) {
            return cachedFrame
        }
        let fs = framesetter ?? CTFramesetterCreateWithAttributedString(attributed)
        framesetter = fs
        let path = CGPath(rect: CGRect(origin: .zero, size: bounds.size), transform: nil)
        let frame = CTFramesetterCreateFrame(fs, CFRangeMake(clamped.location, clamped.length), path, nil)
        cachedFrame = frame
        cachedFrameSize = bounds.size
        cachedFrameRange = clamped
        return frame
    }

    private func clampedPageRange(in length: Int) -> NSRange {
        let loc = min(max(0, pageRange.location), length)
        let len = min(pageRange.length, length - loc)
        return NSRange(location: loc, length: max(0, len))
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        // 页面必须自绘底色：翻页转场时两页会短暂重叠，透明页会把下面那页的字透出来
        ctx.setFillColor(theme.uiBackground.cgColor)
        ctx.fill(bounds)
        if theme.paper { drawGrain(in: ctx) }

        guard let frame = makeFrame() else { return }
        ctx.textMatrix = .identity
        ctx.translateBy(x: 0, y: bounds.height)
        ctx.scaleBy(x: 1, y: -1)

        drawHighlights(in: ctx, frame: frame)
        drawSpeaking(in: ctx, frame: frame)
        CTFrameDraw(frame, ctx)
        drawSelection(in: ctx, frame: frame)
    }

    /// 极轻的纸纹，让长时间阅读时页面不至于「太平」
    private func drawGrain(in ctx: CGContext) {
        let color = (theme.isDark ? UIColor.white.withAlphaComponent(0.014)
                                  : UIColor.black.withAlphaComponent(0.022)).cgColor
        ctx.setFillColor(color)
        let step: CGFloat = 7
        var y: CGFloat = 0
        var row = 0
        while y < bounds.height {
            var x: CGFloat = (row % 2 == 0) ? 0 : step / 2
            while x < bounds.width {
                ctx.fillEllipse(in: CGRect(x: x, y: y, width: 1.1, height: 1.1))
                x += step
            }
            y += step
            row += 1
        }
    }

    private func drawHighlights(in ctx: CGContext, frame: CTFrame) {
        guard !highlights.isEmpty else { return }
        for h in highlights {
            let attrRange = NSRange(location: bodyStart + h.location, length: h.length)
            let rects = rects(for: attrRange, in: frame)
            guard !rects.isEmpty else { continue }
            let base = UIColor(hex: Highlight.palette[min(max(0, h.colorIndex), Highlight.palette.count - 1)])
            switch h.style {
            case .background:
                ctx.setFillColor(base.withAlphaComponent(theme.isDark ? 0.26 : 0.52).cgColor)
                for r in rects {
                    let box = r.insetBy(dx: -1, dy: -1)
                    let p = UIBezierPath(roundedRect: box, cornerRadius: 3)
                    ctx.addPath(p.cgPath)
                    ctx.fillPath()
                }
            case .underline:
                ctx.setStrokeColor(base.withAlphaComponent(theme.isDark ? 0.7 : 0.95).cgColor)
                ctx.setLineWidth(1.4)
                for r in rects {
                    ctx.move(to: CGPoint(x: r.minX, y: r.minY - 1))
                    ctx.addLine(to: CGPoint(x: r.maxX, y: r.minY - 1))
                }
                ctx.strokePath()
            case .wavy:
                ctx.setStrokeColor(base.withAlphaComponent(theme.isDark ? 0.75 : 1).cgColor)
                ctx.setLineWidth(1.2)
                for r in rects { addWave(ctx, from: r.minX, to: r.maxX, y: r.minY - 1) }
                ctx.strokePath()
            }
            if h.hasNote {
                ctx.setFillColor(Brand.uiOrange.withAlphaComponent(0.85).cgColor)
                if let last = rects.last {
                    ctx.fillEllipse(in: CGRect(x: last.maxX + 2, y: last.midY - 2, width: 4, height: 4))
                }
            }
        }
    }

    private func addWave(_ ctx: CGContext, from x0: CGFloat, to x1: CGFloat, y: CGFloat) {
        let step: CGFloat = 3
        var x = x0
        ctx.move(to: CGPoint(x: x, y: y))
        var up = true
        while x < x1 {
            let nx = min(x + step, x1)
            ctx.addQuadCurve(to: CGPoint(x: nx, y: y),
                             control: CGPoint(x: (x + nx) / 2, y: y + (up ? 2.2 : -2.2)))
            up.toggle()
            x = nx
        }
    }

    private func drawSpeaking(in ctx: CGContext, frame: CTFrame) {
        guard let speakingRange else { return }
        let attrRange = NSRange(location: bodyStart + speakingRange.location, length: speakingRange.length)
        let rects = rects(for: attrRange, in: frame)
        guard !rects.isEmpty else { return }
        ctx.setFillColor(Brand.uiOrange.withAlphaComponent(theme.isDark ? 0.22 : 0.16).cgColor)
        for r in rects {
            let p = UIBezierPath(roundedRect: r.insetBy(dx: -2, dy: -2), cornerRadius: 4)
            ctx.addPath(p.cgPath)
            ctx.fillPath()
        }
    }

    private func drawSelection(in ctx: CGContext, frame: CTFrame) {
        guard let selectionRange, selectionRange.length > 0 else { return }
        let rects = rects(for: selectionRange, in: frame)
        guard !rects.isEmpty else { return }
        let tint = Brand.uiOrange
        ctx.setFillColor(tint.withAlphaComponent(theme.isDark ? 0.32 : 0.24).cgColor)
        for r in rects {
            let p = UIBezierPath(roundedRect: r.insetBy(dx: -1, dy: -1), cornerRadius: 3)
            ctx.addPath(p.cgPath)
            ctx.fillPath()
        }
        // 选区手柄
        if let first = rects.first, let last = rects.last {
            ctx.setFillColor(tint.cgColor)
            ctx.setStrokeColor(tint.cgColor)
            ctx.setLineWidth(2)
            ctx.move(to: CGPoint(x: first.minX, y: first.minY))
            ctx.addLine(to: CGPoint(x: first.minX, y: first.maxY))
            ctx.strokePath()
            ctx.fillEllipse(in: CGRect(x: first.minX - handleRadius, y: first.maxY - handleRadius / 2,
                                       width: handleRadius * 2, height: handleRadius * 2))
            ctx.move(to: CGPoint(x: last.maxX, y: last.minY))
            ctx.addLine(to: CGPoint(x: last.maxX, y: last.maxY))
            ctx.strokePath()
            ctx.fillEllipse(in: CGRect(x: last.maxX - handleRadius, y: last.minY - handleRadius * 1.5,
                                       width: handleRadius * 2, height: handleRadius * 2))
        }
    }

    // MARK: - 几何计算（CoreText 坐标系，原点左下）

    private func lineInfos(in frame: CTFrame) -> [(line: CTLine, origin: CGPoint)] {
        guard let lines = CTFrameGetLines(frame) as? [CTLine], !lines.isEmpty else { return [] }
        var origins = [CGPoint](repeating: .zero, count: lines.count)
        CTFrameGetLineOrigins(frame, CFRangeMake(0, 0), &origins)
        return Array(zip(lines, origins))
    }

    private func rects(for range: NSRange, in frame: CTFrame) -> [CGRect] {
        guard range.length > 0 else { return [] }
        var result: [CGRect] = []
        for (line, origin) in lineInfos(in: frame) {
            let lineRange = CTLineGetStringRange(line)
            let lr = NSRange(location: lineRange.location, length: lineRange.length)
            let inter = NSIntersectionRange(lr, range)
            guard inter.length > 0 else { continue }
            var ascent: CGFloat = 0, descent: CGFloat = 0, leading: CGFloat = 0
            CTLineGetTypographicBounds(line, &ascent, &descent, &leading)
            let startX = CTLineGetOffsetForStringIndex(line, inter.location, nil)
            let endX = CTLineGetOffsetForStringIndex(line, inter.location + inter.length, nil)
            let rect = CGRect(x: origin.x + min(startX, endX),
                              y: origin.y - descent,
                              width: abs(endX - startX),
                              height: ascent + descent)
            if rect.width > 0.5 { result.append(rect) }
        }
        return result
    }

    /// UIKit 坐标 -> 富文本字符下标
    func characterIndex(at point: CGPoint) -> Int? {
        guard let frame = makeFrame() else { return nil }
        let p = CGPoint(x: point.x, y: bounds.height - point.y)
        let infos = lineInfos(in: frame)
        guard !infos.isEmpty else { return nil }

        var chosen = infos[0]
        var bestDistance = CGFloat.greatestFiniteMagnitude
        for info in infos {
            var ascent: CGFloat = 0, descent: CGFloat = 0, leading: CGFloat = 0
            CTLineGetTypographicBounds(info.line, &ascent, &descent, &leading)
            let top = info.origin.y + ascent
            let bottom = info.origin.y - descent
            if p.y <= top && p.y >= bottom {
                chosen = info
                bestDistance = 0
                break
            }
            let d = min(abs(p.y - top), abs(p.y - bottom))
            if d < bestDistance { bestDistance = d; chosen = info }
        }
        let localX = p.x - chosen.origin.x
        let idx = CTLineGetStringIndexForPosition(chosen.line, CGPoint(x: localX, y: 0))
        return idx == kCFNotFound ? nil : idx
    }

    /// 选区（富文本坐标）在本视图中的包围盒，UIKit 坐标
    func selectionBoundingBox() -> CGRect? {
        guard let selectionRange, selectionRange.length > 0, let frame = makeFrame() else { return nil }
        let rects = rects(for: selectionRange, in: frame)
        guard !rects.isEmpty else { return nil }
        let union = rects.dropFirst().reduce(rects[0]) { $0.union($1) }
        return CGRect(x: union.minX, y: bounds.height - union.maxY,
                      width: union.width, height: union.height)
    }

    /// 点中了哪条划线（返回章节内偏移的 Highlight）
    func highlight(at point: CGPoint) -> Highlight? {
        guard let frame = makeFrame() else { return nil }
        let p = CGPoint(x: point.x, y: bounds.height - point.y)
        for h in highlights {
            let attrRange = NSRange(location: bodyStart + h.location, length: h.length)
            for r in rects(for: attrRange, in: frame) where r.insetBy(dx: -3, dy: -5).contains(p) {
                return h
            }
        }
        return nil
    }

    /// 页面第一个可见字符对应的章节内偏移
    var firstContentOffset: Int { max(0, pageRange.location - bodyStart) }

    // MARK: - 取词与选择

    private func wordRange(at index: Int) -> NSRange {
        guard let attributed else { return NSRange(location: index, length: 1) }
        let ns = attributed.string as NSString
        guard index >= 0, index < ns.length else { return NSRange(location: max(0, ns.length - 1), length: 1) }

        let tokenizer = CFStringTokenizerCreate(kCFAllocatorDefault, ns as CFString,
                                                CFRangeMake(0, ns.length),
                                                kCFStringTokenizerUnitWordBoundary,
                                                CFLocaleCopyCurrent())
        let type = CFStringTokenizerGoToTokenAtIndex(tokenizer, index)
        if type.rawValue != 0 {
            let r = CFStringTokenizerGetCurrentTokenRange(tokenizer)
            if r.length > 0 { return NSRange(location: r.location, length: r.length) }
        }
        return NSRange(location: index, length: 1)
    }

    private func setupGestures() {
        let long = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
        long.minimumPressDuration = 0.32
        long.allowableMovement = 12
        addGestureRecognizer(long)

        let pan = UIPanGestureRecognizer(target: self, action: #selector(handleHandlePan(_:)))
        pan.delegate = self
        addGestureRecognizer(pan)
    }

    @objc private func handleLongPress(_ g: UILongPressGestureRecognizer) {
        guard selectionEnabled, let attributed else { return }
        let point = g.location(in: self)
        switch g.state {
        case .began:
            guard let idx = characterIndex(at: point) else { return }
            let word = wordRange(at: idx)
            selectionAnchorIndex = word.location
            selectionRange = clamp(word, limit: attributed.length)
            Haptics.tap()
            notifySelection(ended: false)
        case .changed:
            guard let anchor = selectionAnchorIndex, let idx = characterIndex(at: point) else { return }
            let lower = min(anchor, idx)
            let upper = max(anchor + 1, idx + 1)
            selectionRange = clamp(NSRange(location: lower, length: upper - lower), limit: attributed.length)
            notifySelection(ended: false)
        case .ended, .cancelled, .failed:
            notifySelection(ended: true)
        default:
            break
        }
    }

    @objc private func handleHandlePan(_ g: UIPanGestureRecognizer) {
        guard selectionEnabled, let attributed, let current = selectionRange else { return }
        let point = g.location(in: self)
        switch g.state {
        case .began:
            dragTarget = nearestHandle(to: point) ?? .none
        case .changed:
            guard dragTarget != .none, let idx = characterIndex(at: point) else { return }
            var newRange = current
            if dragTarget == .head {
                let end = current.location + current.length
                let loc = min(idx, end - 1)
                newRange = NSRange(location: max(0, loc), length: end - max(0, loc))
            } else {
                let end = max(current.location + 1, idx + 1)
                newRange = NSRange(location: current.location, length: end - current.location)
            }
            selectionRange = clamp(newRange, limit: attributed.length)
            notifySelection(ended: false)
        case .ended, .cancelled, .failed:
            dragTarget = .none
            notifySelection(ended: true)
        default:
            break
        }
    }

    private func nearestHandle(to point: CGPoint) -> DragTarget? {
        guard let selectionRange, let frame = makeFrame() else { return nil }
        let rects = rects(for: selectionRange, in: frame)
        guard let first = rects.first, let last = rects.last else { return nil }
        let head = CGPoint(x: first.minX, y: bounds.height - first.minY)
        let tail = CGPoint(x: last.maxX, y: bounds.height - last.maxY)
        let dh = hypot(point.x - head.x, point.y - head.y)
        let dt = hypot(point.x - tail.x, point.y - tail.y)
        let threshold: CGFloat = 34
        if min(dh, dt) > threshold { return nil }
        return dh < dt ? .head : .tail
    }

    private func clamp(_ range: NSRange, limit: Int) -> NSRange {
        let loc = min(max(0, range.location), max(0, limit - 1))
        let len = min(range.length, limit - loc)
        return NSRange(location: loc, length: max(0, len))
    }

    private func notifySelection(ended: Bool) {
        let box = selectionBoundingBox() ?? .zero
        let anchor = convert(box, to: window)
        if ended {
            delegate?.pageTextView(self, selectionEnded: selectionRange, anchor: anchor)
        } else {
            delegate?.pageTextView(self, selectionChanged: selectionRange, anchor: anchor)
        }
    }

    func clearSelection() {
        guard selectionRange != nil else { return }
        selectionRange = nil
        selectionAnchorIndex = nil
    }

    /// 选中文本内容（章节内偏移 + 文本）
    var selectedContent: (range: NSRange, text: String)? {
        guard let selectionRange, selectionRange.length > 0, let attributed else { return nil }
        let ns = attributed.string as NSString
        let safe = clamp(selectionRange, limit: ns.length)
        guard safe.length > 0 else { return nil }
        let text = ns.substring(with: safe)
        return (NSRange(location: max(0, safe.location - bodyStart), length: safe.length), text)
    }

    // MARK: - 手势仲裁

    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        // 只有靠近选区手柄时才拦截拖动，否则把手势让给翻页
        if let pan = gestureRecognizer as? UIPanGestureRecognizer, pan.view === self {
            guard selectionRange != nil else { return false }
            return nearestHandle(to: pan.location(in: self)) != nil
        }
        return super.gestureRecognizerShouldBegin(gestureRecognizer)
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        false
    }
}
