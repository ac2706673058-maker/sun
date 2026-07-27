import SwiftUI
import UIKit

// MARK: - 通用样式

extension ReadingTheme {
    /// 菜单面板底色：夜间用更深一档的灰，日间用纯白，避免和正文底色糊在一起
    var surface: Color {
        isDark ? Color(hex: "#1F2023") : Color.white
    }
    var surfaceText: Color {
        isDark ? Color(hex: "#D8D5CF") : Color(hex: "#2C2C2E")
    }
    var surfaceMuted: Color {
        isDark ? Color(hex: "#7C7A75") : Color(hex: "#9C9CA0")
    }
    var separator: Color {
        isDark ? Color.white.opacity(0.08) : Color.black.opacity(0.06)
    }
    var control: Color {
        isDark ? Color.white.opacity(0.07) : Color.black.opacity(0.045)
    }
}

// MARK: - 顶栏

struct ReaderTopBar: View {
    let book: Book
    let theme: ReadingTheme
    let onShelf: Bool
    var onBack: () -> Void
    var onAddShelf: () -> Void
    var onShare: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .medium))
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(book.title)
                    .font(.system(size: 15, weight: .semibold))
                    .lineLimit(1)
                Text(book.author)
                    .font(.system(size: 11))
                    .foregroundStyle(theme.surfaceMuted)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            if !onShelf {
                Button(action: onAddShelf) {
                    Text("加入书架")
                        .font(.system(size: 12, weight: .medium))
                        .padding(.horizontal, 11).padding(.vertical, 6)
                        .background(Brand.orange, in: Capsule())
                        .foregroundStyle(.white)
                }
            }
            Menu {
                Button { onShare() } label: { Label("分享本书", systemImage: "square.and.arrow.up") }
                Button { UIPasteboard.general.string = book.title } label: { Label("复制书名", systemImage: "doc.on.doc") }
                Button { } label: { Label("报告内容问题", systemImage: "exclamationmark.bubble") }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 17, weight: .medium))
                    .frame(width: 28, height: 28)
            }
        }
        .foregroundStyle(theme.surfaceText)
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
        .padding(.top, 6)
        .background {
            theme.surface
                .ignoresSafeArea(edges: .top)
                .shadow(color: .black.opacity(theme.isDark ? 0.4 : 0.06), radius: 10, y: 4)
        }
    }
}

// MARK: - 底栏

struct ReaderBottomBar: View {
    let theme: ReadingTheme
    @ObservedObject var engine: ReaderEngine
    @Binding var panel: ReaderPanel
    let isNight: Bool
    var onCatalog: () -> Void
    var onToggleNight: () -> Void
    var onSeek: (Double) -> Void
    var onPrevChapter: () -> Void
    var onNextChapter: () -> Void

    @State private var dragValue: Double = 0
    @State private var dragging = false

    private var progress: Double { dragging ? dragValue : engine.bookProgress }

    var body: some View {
        VStack(spacing: 0) {
            // 进度
            HStack(spacing: 12) {
                Button(action: { onPrevChapter(); Haptics.tick() }) {
                    Text("上一章").font(.system(size: 13))
                }
                .disabled(engine.current.chapter == 0)

                ReaderSlider(value: Binding(
                    get: { progress },
                    set: { dragValue = $0 }
                ), theme: theme, onEditingChanged: { editing in
                    dragging = editing
                    if editing {
                        dragValue = engine.bookProgress
                    } else {
                        onSeek(dragValue)
                        Haptics.tick()
                    }
                })

                Button(action: { onNextChapter(); Haptics.tick() }) {
                    Text("下一章").font(.system(size: 13))
                }
                .disabled(engine.current.chapter >= engine.chapterCount - 1)
            }
            .foregroundStyle(theme.surfaceText)
            .padding(.horizontal, 16)
            .padding(.top, 14)

            Text(dragging
                 ? "\(engine.chapter(at: chapterIndex(for: dragValue))?.title ?? "") · \(Int(dragValue * 100))%"
                 : "\(engine.currentChapter?.title ?? "") · 本章 \(engine.chapterProgressText)")
                .font(.system(size: 11))
                .foregroundStyle(theme.surfaceMuted)
                .lineLimit(1)
                .padding(.top, 6)
                .padding(.horizontal, 20)

            // 功能按钮
            HStack(spacing: 0) {
                barItem("目录", "list.bullet") { onCatalog() }
                barItem(isNight ? "日间" : "夜间", isNight ? "sun.max" : "moon") { onToggleNight() }
                barItem("亮度", "sun.max.circle") { toggle(.brightness) }
                barItem("设置", "textformat.size") { toggle(.typography) }
                barItem("听书", "headphones") { toggle(.speech) }
                barItem("更多", "ellipsis.circle") { toggle(.more) }
            }
            .padding(.top, 12)
            .padding(.bottom, 6)
        }
        .background {
            theme.surface
                .ignoresSafeArea(edges: .bottom)
                .shadow(color: .black.opacity(theme.isDark ? 0.4 : 0.06), radius: 10, y: -4)
        }
    }

    private func chapterIndex(for p: Double) -> Int {
        min(engine.chapterCount - 1, max(0, Int(p * Double(engine.chapterCount))))
    }

    private func toggle(_ target: ReaderPanel) {
        Haptics.tap()
        panel = (panel == target) ? .none : target
    }

    private func barItem(_ title: String, _ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: icon).font(.system(size: 19, weight: .regular))
                Text(title).font(.system(size: 10))
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .foregroundStyle(theme.surfaceText)
    }
}

/// 自绘滑杆：命中区域更大，拖动时有刻度吸附的轻微震动
struct ReaderSlider: View {
    @Binding var value: Double
    let theme: ReadingTheme
    var onEditingChanged: (Bool) -> Void

    @State private var lastTick = 0

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let x = max(0, min(w, w * value))
            ZStack(alignment: .leading) {
                Capsule().fill(theme.control).frame(height: 3)
                Capsule().fill(Brand.orange).frame(width: x, height: 3)
                Circle()
                    .fill(Color.white)
                    .frame(width: 15, height: 15)
                    .shadow(color: .black.opacity(0.18), radius: 3, y: 1)
                    .overlay(Circle().stroke(Brand.orange, lineWidth: 2))
                    .offset(x: x - 7.5)
            }
            .frame(height: 34)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { g in
                        onEditingChanged(true)
                        let p = min(1, max(0, g.location.x / max(1, w)))
                        value = p
                        let tick = Int(p * 40)
                        if tick != lastTick { lastTick = tick; Haptics.tick() }
                    }
                    .onEnded { _ in onEditingChanged(false) }
            )
        }
        .frame(height: 34)
    }
}

// MARK: - 电量

struct BatteryBadge: View {
    let level: Double
    let charging: Bool
    let color: Color

    var body: some View {
        HStack(spacing: 3) {
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 2.2)
                    .stroke(color.opacity(0.7), lineWidth: 1)
                    .frame(width: 19, height: 10)
                RoundedRectangle(cornerRadius: 1.2)
                    .fill(level < 0.2 ? Color(hex: "#E5533D") : color.opacity(0.85))
                    .frame(width: max(1.5, 17 * level), height: 6.5)
                    .padding(.leading, 1)
            }
            .overlay(alignment: .trailing) {
                RoundedRectangle(cornerRadius: 0.6)
                    .fill(color.opacity(0.7))
                    .frame(width: 1.6, height: 4)
                    .offset(x: 2.4)
            }
            if charging {
                Image(systemName: "bolt.fill").font(.system(size: 7))
            }
            Text("\(Int(level * 100))%")
        }
    }
}

// MARK: - 选中文字气泡

struct SelectionBubble: View {
    let payload: SelectionPayload
    let theme: ReadingTheme
    let existingHighlight: Highlight?
    let containerSize: CGSize
    var onCopy: () -> Void
    var onHighlight: (HighlightStyle, Int) -> Void
    var onNote: () -> Void
    var onSpeak: () -> Void
    var onRemove: () -> Void
    var onDismiss: () -> Void

    @State private var showStyles = false
    @State private var colorIndex = 0
    @State private var style: HighlightStyle = .background

    private var bubbleWidth: CGFloat { showStyles ? 268 : 300 }

    /// 选区在屏幕上方就把气泡放下面，反之放上面，尽量不遮住正在读的句子
    private var position: CGPoint {
        let anchor = payload.anchor
        let preferAbove = anchor.minY > containerSize.height * 0.42
        let y = preferAbove ? anchor.minY - 34 : anchor.maxY + 34
        let clampedY = min(max(70, y), containerSize.height - 80)
        let x = min(max(bubbleWidth / 2 + 12, anchor.midX), containerSize.width - bubbleWidth / 2 - 12)
        return CGPoint(x: x, y: clampedY)
    }

    var body: some View {
        VStack(spacing: 0) {
            if showStyles {
                HStack(spacing: 14) {
                    ForEach(Array(Highlight.palette.enumerated()), id: \.offset) { idx, hex in
                        Button {
                            colorIndex = idx
                            onHighlight(style, idx)
                        } label: {
                            Circle()
                                .fill(Color(hex: hex))
                                .frame(width: 22, height: 22)
                                .overlay(Circle().stroke(Color.white.opacity(0.7), lineWidth: colorIndex == idx ? 2 : 0))
                        }
                    }
                    Divider().frame(height: 18)
                    ForEach(HighlightStyle.allCases) { s in
                        Button { style = s; onHighlight(s, colorIndex) } label: {
                            Text(s.name)
                                .font(.system(size: 11))
                                .foregroundStyle(style == s ? Brand.orange : theme.surfaceMuted)
                        }
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
            } else {
                HStack(spacing: 0) {
                    item("复制", "doc.on.doc", action: onCopy)
                    item(existingHighlight == nil ? "划线" : "改颜色", "highlighter") {
                        withAnimation(.easeOut(duration: 0.16)) { showStyles = true }
                    }
                    item("想法", "text.bubble", action: onNote)
                    item("朗读", "speaker.wave.2", action: onSpeak)
                    if existingHighlight != nil {
                        item("删除", "trash", action: onRemove)
                    } else {
                        item("搜索", "magnifyingglass") {
                            let q = payload.text.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
                            if let url = URL(string: "https://www.baidu.com/s?wd=\(q)") {
                                UIApplication.shared.open(url)
                            }
                        }
                    }
                }
                .padding(.horizontal, 6)
                .padding(.vertical, 8)
            }
        }
        .background {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(theme.isDark ? Color(hex: "#2B2C30") : Color(hex: "#2F3033"))
                .shadow(color: .black.opacity(0.28), radius: 14, y: 6)
        }
        .frame(width: bubbleWidth)
        .position(position)
    }

    private func item(_ title: String, _ icon: String, action: @escaping () -> Void) -> some View {
        Button {
            Haptics.tap()
            action()
        } label: {
            VStack(spacing: 3) {
                Image(systemName: icon).font(.system(size: 15))
                Text(title).font(.system(size: 10))
            }
            .frame(maxWidth: .infinity)
            .foregroundStyle(.white)
            .contentShape(Rectangle())
        }
    }
}

// MARK: - 想法编辑

struct NoteEditor: View {
    @State var highlight: Highlight
    let theme: ReadingTheme
    var onSave: (Highlight) -> Void
    var onDelete: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 14) {
                Text(highlight.text)
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(hex: Highlight.palette[min(highlight.colorIndex, Highlight.palette.count - 1)]).opacity(0.35),
                                in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .lineLimit(4)

                TextEditor(text: $highlight.note)
                    .focused($focused)
                    .font(.system(size: 15))
                    .frame(minHeight: 120)
                    .overlay(alignment: .topLeading) {
                        if highlight.note.isEmpty {
                            Text("写下这一刻的想法…")
                                .font(.system(size: 15))
                                .foregroundStyle(.tertiary)
                                .padding(.top, 8)
                                .padding(.leading, 5)
                                .allowsHitTesting(false)
                        }
                    }
                Spacer()
            }
            .padding(16)
            .navigationTitle("我的想法")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("删除划线", role: .destructive) {
                        onDelete(highlight.id)
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        onSave(highlight)
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
            .onAppear { focused = true }
        }
    }
}

// MARK: - 轻提示

struct ToastView: View {
    let text: String
    let theme: ReadingTheme

    var body: some View {
        Text(text)
            .font(.system(size: 13, weight: .medium))
            .foregroundStyle(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 9)
            .background(Color.black.opacity(0.78), in: Capsule())
            .shadow(color: .black.opacity(0.2), radius: 10, y: 4)
    }
}

// MARK: - 护眼提醒

struct RestReminderView: View {
    let theme: ReadingTheme
    let minutes: Int
    var onDismiss: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.5).ignoresSafeArea()
                .onTapGesture { onDismiss() }
            VStack(spacing: 14) {
                Image(systemName: "eye")
                    .font(.system(size: 34))
                    .foregroundStyle(Brand.orange)
                Text("已经读了 \(max(1, minutes)) 分钟")
                    .font(.system(size: 17, weight: .semibold))
                Text("抬头看看远处，让眼睛歇 20 秒")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                Button("继续阅读") { onDismiss() }
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 28).padding(.vertical, 10)
                    .background(Brand.gradient, in: Capsule())
            }
            .padding(26)
            .background(theme.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .padding(40)
        }
        .transition(.opacity)
    }
}

// MARK: - 自动阅读悬浮条

struct AutoReadFloatingBar: View {
    let theme: ReadingTheme
    @Binding var speed: Double
    var onExit: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button { speed = max(0, speed - 0.1); Haptics.tick() } label: {
                Image(systemName: "minus")
            }
            VStack(spacing: 2) {
                Text("自动阅读").font(.system(size: 11, weight: .medium))
                Text("速度 \(Int(speed * 100))").font(.system(size: 10)).foregroundStyle(.secondary)
            }
            .frame(width: 62)
            Button { speed = min(1, speed + 0.1); Haptics.tick() } label: {
                Image(systemName: "plus")
            }
            Divider().frame(height: 20)
            Button("退出") { onExit() }
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(Brand.orange)
        }
        .font(.system(size: 14, weight: .medium))
        .foregroundStyle(theme.surfaceText)
        .padding(.horizontal, 18)
        .padding(.vertical, 11)
        .background(theme.surface, in: Capsule())
        .shadow(color: .black.opacity(0.18), radius: 12, y: 4)
    }
}
