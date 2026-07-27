import SwiftUI
import UIKit

enum ReaderPanel: Equatable {
    case none, typography, theme, brightness, speech, auto, more
}

@MainActor
struct ReaderView: View {
    let book: Book

    @StateObject private var engine: ReaderEngine
    @StateObject private var speech = SpeechController()
    @StateObject private var autoRead = AutoReadController()
    @StateObject private var brightness = BrightnessManager()
    @StateObject private var device = DeviceStatusMonitor()
    @StateObject private var timer = ReadingTimer()
    @StateObject private var volumeKeys = VolumeKeyMonitor()

    @ObservedObject private var settings = ReaderSettings.shared
    @EnvironmentObject private var store: LibraryStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var systemScheme

    @State private var menuVisible = false
    @State private var panel: ReaderPanel = .none
    @State private var showCatalog = false
    @State private var selection: SelectionPayload?
    @State private var editingHighlight: Highlight?
    @State private var noteDraft = ""
    @State private var toast: String?
    @State private var showAddShelfPrompt = false
    @State private var controller: ReaderPagerController?
    @State private var showRestReminder = false

    init(book: Book) {
        self.book = book
        let progress = LibraryStore.shared.readingProgress(for: book.id)
        _engine = StateObject(wrappedValue: ReaderEngine(book: book, settings: .shared, start: progress))
    }

    private var theme: ReadingTheme { settings.effectiveTheme }
    private var config: ReaderConfig { settings.config }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .top) {
                background

                ReaderPager(
                    engine: engine,
                    settings: settings,
                    highlights: store.highlightList(bookID: book.id),
                    speakingRange: speech.spokenRange,
                    contentInsets: insets(geo),
                    onTapMenu: { toggleMenu() },
                    onSelection: { handleSelection($0) },
                    onPageChanged: { handlePageChanged() },
                    onBoundary: { atEnd in handleBoundary(atEnd) },
                    controllerRef: { c in DispatchQueue.main.async { controller = c } }
                )
                .ignoresSafeArea()

                chrome(geo)

                // 暖光滤镜：叠在正文之上、菜单之下，不拦截手势
                if config.warmFilter > 0.01 {
                    Color(hex: "#FF9B2F")
                        .opacity(config.warmFilter * 0.38)
                        .blendMode(.multiply)
                        .allowsHitTesting(false)
                        .ignoresSafeArea()
                }

                if let selection, panel == .none {
                    SelectionBubble(
                        payload: selection,
                        theme: theme,
                        existingHighlight: existingHighlight(for: selection),
                        containerSize: geo.size,
                        onCopy: { copySelection(selection) },
                        onHighlight: { style, colorIndex in addHighlight(selection, style: style, colorIndex: colorIndex) },
                        onNote: { startNote(selection) },
                        onSpeak: { speakFrom(selection) },
                        onRemove: { removeHighlight(selection) },
                        onDismiss: { clearSelection() }
                    )
                    .transition(.scale(scale: 0.92).combined(with: .opacity))
                }

                overlays(geo)
            }
            .animation(.easeOut(duration: 0.22), value: menuVisible)
            .animation(.easeOut(duration: 0.22), value: panel)
            .animation(.easeOut(duration: 0.16), value: selection?.range.location)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .statusBarHidden(config.fullScreenMode && !menuVisible)
        .preferredColorScheme(theme.colorScheme)
        .persistentSystemOverlays(menuVisible ? .automatic : .hidden)
        .sheet(isPresented: $showCatalog) {
            CatalogSheet(book: book, engine: engine, theme: theme) { chapter in
                engine.jump(toChapter: chapter)
                controller?.syncToEngine(animated: false)
                handlePageChanged()
            }
            .presentationDetents([.large])
        }
        .sheet(item: $editingHighlight) { h in
            NoteEditor(highlight: h, theme: theme) { updated in
                store.updateHighlight(updated)
                Haptics.success()
                showToast("想法已保存")
            } onDelete: { id in
                store.removeHighlight(id: id)
                showToast("已删除")
            }
            .presentationDetents([.medium])
        }
        .confirmationDialog("要把《\(book.title)》放进书架吗？", isPresented: $showAddShelfPrompt, titleVisibility: .visible) {
            Button("加入书架") { store.addToShelf(book.id); leave() }
            Button("退出不保存", role: .destructive) { leave() }
            Button("取消", role: .cancel) { }
        }
        .onAppear { onAppear() }
        .onDisappear { onDisappear() }
        .onChange(of: systemScheme) { _, newValue in
            settings.systemIsDark = (newValue == .dark)
            engine.invalidateTypography()
        }
        .onChange(of: config.fontSize) { _, _ in engine.invalidateTypography() }
        .onChange(of: config.lineHeightMultiple) { _, _ in engine.invalidateTypography() }
        .onChange(of: config.paragraphSpacing) { _, _ in engine.invalidateTypography() }
        .onChange(of: config.letterSpacing) { _, _ in engine.invalidateTypography() }
        .onChange(of: config.fontFamily) { _, _ in engine.invalidateTypography() }
        .onChange(of: config.boldText) { _, _ in engine.invalidateTypography() }
        .onChange(of: config.indentFirstLine) { _, _ in engine.invalidateTypography() }
        .onChange(of: config.justified) { _, _ in engine.invalidateTypography() }
        .onChange(of: config.traditionalChinese) { _, _ in engine.invalidateTypography() }
        .onChange(of: config.themeID) { _, _ in engine.invalidateTypography() }
        .onChange(of: config.customBrightness) { _, v in brightness.apply(custom: v, follow: config.followSystemBrightness) }
        .onChange(of: config.followSystemBrightness) { _, v in brightness.apply(custom: config.customBrightness, follow: v) }
        .onChange(of: config.volumeKeyTurnPage) { _, v in v ? volumeKeys.start() : volumeKeys.stop() }
        .onChange(of: timer.shouldRest) { _, v in
            if v { showRestReminder = true; timer.shouldRest = false }
        }
        .overlay(alignment: .top) {
            if let toast {
                ToastView(text: toast, theme: theme)
                    .padding(.top, 60)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .overlay {
            if showRestReminder {
                RestReminderView(theme: theme, minutes: timer.elapsed / 60) {
                    showRestReminder = false
                }
            }
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.85), value: toast)
    }

    // MARK: - 背景

    /// 页边区域的底色。正文区域的底色与纸纹由 PageTextView 自己绘制，
    /// 这样翻页时纸纹跟着页面一起走。
    private var background: some View {
        theme.backgroundColor.ignoresSafeArea()
    }

    private func insets(_ geo: GeometryProxy) -> UIEdgeInsets {
        let top = geo.safeAreaInsets.top + (config.showHeader ? 26 : 6) + config.topMargin
        let bottom = geo.safeAreaInsets.bottom + (config.showFooter ? 24 : 6) + config.bottomMargin
        return UIEdgeInsets(top: top, left: config.horizontalMargin,
                            bottom: bottom, right: config.horizontalMargin)
    }

    // MARK: - 页眉页脚

    @ViewBuilder
    private func chrome(_ geo: GeometryProxy) -> some View {
        VStack(spacing: 0) {
            if config.showHeader {
                HStack {
                    Text(engine.currentChapter?.title ?? book.title)
                        .lineLimit(1)
                    Spacer(minLength: 12)
                    if speech.isSpeaking {
                        Label("朗读中", systemImage: "waveform")
                            .labelStyle(.titleAndIcon)
                    } else if autoRead.isRunning {
                        Label("自动", systemImage: "play.circle")
                    }
                }
                .font(.system(size: 11))
                .foregroundStyle(theme.secondaryColor)
                .padding(.horizontal, config.horizontalMargin)
                .padding(.top, 4)
                .frame(height: 22)
            }
            Spacer(minLength: 0)
            if config.showFooter {
                HStack(spacing: 8) {
                    if config.showBattery {
                        BatteryBadge(level: device.batteryLevel, charging: device.isCharging,
                                     color: theme.secondaryColor)
                    }
                    if config.showClock {
                        Text(device.clock)
                    }
                    Spacer(minLength: 8)
                    if config.showChapterPageCount {
                        Text("本章 \(engine.chapterProgressText)")
                    }
                    if config.showPagePercent {
                        Text(engine.percentText)
                    }
                }
                .font(.system(size: 11).monospacedDigit())
                .foregroundStyle(theme.secondaryColor)
                .padding(.horizontal, config.horizontalMargin)
                .padding(.bottom, 4)
                .frame(height: 20)
            }
        }
        .allowsHitTesting(false)
        .frame(maxHeight: .infinity)
    }

    // MARK: - 菜单层

    @ViewBuilder
    private func overlays(_ geo: GeometryProxy) -> some View {
        ZStack(alignment: .bottom) {
            if menuVisible {
                Color.black.opacity(0.001)
                    .ignoresSafeArea()
                    .onTapGesture { closeMenu() }

                ReaderTopBar(
                    book: book,
                    theme: theme,
                    onShelf: store.isOnShelf(book.id),
                    onBack: { attemptLeave() },
                    onAddShelf: {
                        store.addToShelf(book.id)
                        Haptics.success()
                        showToast("已加入书架")
                    },
                    onShare: { showToast("已复制书籍信息") }
                )
                .frame(maxHeight: .infinity, alignment: .top)
                .transition(.move(edge: .top).combined(with: .opacity))

                VStack(spacing: 0) {
                    switch panel {
                    case .typography:
                        TypographyPanel(settings: settings, theme: theme)
                    case .theme:
                        ThemePanel(settings: settings)
                    case .brightness:
                        BrightnessPanel(settings: settings, theme: theme)
                    case .speech:
                        SpeechPanel(settings: settings, speech: speech, theme: theme,
                                    onStart: { startSpeaking() }, onStop: { speech.stop() })
                    case .auto:
                        AutoReadPanel(settings: settings, autoRead: autoRead, theme: theme,
                                      onStart: { startAutoRead() }, onStop: { autoRead.stop() })
                    case .more:
                        MorePanel(settings: settings, theme: theme,
                                  isBookmarked: isCurrentPageBookmarked,
                                  onToggleBookmark: { toggleBookmark() },
                                  onCatalog: { showCatalog = true; closeMenu() })
                    case .none:
                        EmptyView()
                    }

                    ReaderBottomBar(
                        theme: theme,
                        engine: engine,
                        panel: $panel,
                        isNight: theme.isDark,
                        onCatalog: { showCatalog = true },
                        onToggleNight: { toggleNight() },
                        onSeek: { p in
                            engine.seek(bookProgress: p)
                            controller?.syncToEngine(animated: false)
                            handlePageChanged()
                        },
                        onPrevChapter: {
                            engine.jumpToPreviousChapter()
                            controller?.syncToEngine(animated: false)
                            handlePageChanged()
                        },
                        onNextChapter: {
                            engine.jumpToNextChapter()
                            controller?.syncToEngine(animated: false)
                            handlePageChanged()
                        }
                    )
                }
                .frame(maxHeight: .infinity, alignment: .bottom)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if autoRead.isRunning && !menuVisible {
                AutoReadFloatingBar(theme: theme, speed: $settings.config.autoReadSpeed,
                                    onExit: { autoRead.stop() })
                    .padding(.bottom, 28)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
    }

    // MARK: - 生命周期

    private func onAppear() {
        settings.systemIsDark = (systemScheme == .dark)
        Haptics.prepare()
        store.touchHistory(book.id)
        timer.start(bookID: book.id, restMinutes: config.eyeRestMinutes)
        brightness.apply(custom: config.customBrightness, follow: config.followSystemBrightness)
        UIApplication.shared.isIdleTimerDisabled = config.keepScreenOn
        autoRead.speed = config.autoReadSpeed
        autoRead.onStep = { controller?.turn(next: true) }
        autoRead.onScroll = { dy in controller?.autoScrollStep(dy) }
        speech.onFinish = { advanceSpeechChapter() }
        speech.onProgress = { offset in followSpeech(offset: offset) }
        if config.volumeKeyTurnPage {
            volumeKeys.onUp = { controller?.turn(next: false) }
            volumeKeys.onDown = { controller?.turn(next: true) }
            volumeKeys.start()
        }
        engine.warmNeighbors()
    }

    private func onDisappear() {
        saveProgress()
        timer.stop()
        autoRead.stop()
        speech.stop()
        volumeKeys.stop()
        brightness.restore()
        UIApplication.shared.isIdleTimerDisabled = false
        store.saveNow()
    }

    private func leave() {
        saveProgress()
        dismiss()
    }

    private func attemptLeave() {
        if store.isOnShelf(book.id) {
            leave()
        } else {
            showAddShelfPrompt = true
        }
    }

    private func saveProgress() {
        store.updateProgress(bookID: book.id,
                             chapterIndex: engine.current.chapter,
                             charOffset: engine.currentContentOffset,
                             percent: engine.bookProgress)
    }

    // MARK: - 交互

    private func toggleMenu() {
        if selection != nil { clearSelection(); return }
        if menuVisible { closeMenu() } else { menuVisible = true; Haptics.tap() }
    }

    private func closeMenu() {
        menuVisible = false
        panel = .none
    }

    private func handlePageChanged() {
        saveProgress()
        if autoRead.isRunning && engine.isLastPage {
            autoRead.stop()
            showToast("已读完本书")
        }
    }

    private func handleBoundary(_ atEnd: Bool) {
        showToast(atEnd ? "已经是最后一页了" : "已经是第一页了")
    }

    private func toggleNight() {
        withAnimation(.easeInOut(duration: 0.28)) {
            if theme.isDark {
                settings.config.themeID = "paper"
            } else {
                settings.config.themeID = settings.config.nightThemeID
            }
        }
        Haptics.tick()
    }

    // MARK: - 选择与划线

    private func handleSelection(_ payload: SelectionPayload?) {
        selection = payload
        if payload != nil { autoRead.stop() }
    }

    private func clearSelection() {
        selection = nil
        controller?.clearSelection()
    }

    private func existingHighlight(for payload: SelectionPayload) -> Highlight? {
        store.highlightList(bookID: book.id, chapter: payload.chapter).first {
            NSIntersectionRange($0.range, payload.range).length > 0
        }
    }

    private func copySelection(_ payload: SelectionPayload) {
        UIPasteboard.general.string = payload.text
        showToast("已复制")
        clearSelection()
    }

    private func addHighlight(_ payload: SelectionPayload, style: HighlightStyle, colorIndex: Int) {
        let h = Highlight(bookID: book.id, chapterIndex: payload.chapter,
                          location: payload.range.location, length: payload.range.length,
                          text: payload.text, note: "", colorIndex: colorIndex, style: style)
        store.addHighlight(h)
        Haptics.success()
        showToast("已划线")
        clearSelection()
    }

    private func removeHighlight(_ payload: SelectionPayload) {
        guard let h = existingHighlight(for: payload) else { return }
        store.removeHighlight(id: h.id)
        showToast("已取消划线")
        clearSelection()
    }

    private func startNote(_ payload: SelectionPayload) {
        let existing = existingHighlight(for: payload)
        let h = existing ?? Highlight(bookID: book.id, chapterIndex: payload.chapter,
                                      location: payload.range.location, length: payload.range.length,
                                      text: payload.text)
        if existing == nil { store.addHighlight(h) }
        editingHighlight = h
        clearSelection()
    }

    // MARK: - 书签

    private var isCurrentPageBookmarked: Bool {
        store.bookmark(bookID: book.id, chapter: engine.current.chapter,
                       range: engine.range(for: engine.current)) != nil
    }

    private func toggleBookmark() {
        let range = engine.range(for: engine.current)
        if let existing = store.bookmark(bookID: book.id, chapter: engine.current.chapter, range: range) {
            store.removeBookmark(id: existing.id)
            showToast("已移除书签")
        } else {
            let text = engine.pageText(for: engine.current)
            let excerpt = String(text.prefix(42)).trimmingCharacters(in: .whitespacesAndNewlines)
            let mark = Bookmark(bookID: book.id, chapterIndex: engine.current.chapter,
                                chapterTitle: engine.currentChapter?.title ?? "",
                                charOffset: engine.currentContentOffset, excerpt: excerpt)
            store.addBookmark(mark)
            Haptics.success()
            showToast("已添加书签")
        }
    }

    // MARK: - 听书 / 自动阅读

    private func startSpeaking() {
        autoRead.stop()
        guard let chapter = engine.currentChapter else { return }
        let offset = engine.currentContentOffset
        let ns = chapter.content as NSString
        let safe = min(max(0, offset), max(0, ns.length - 1))
        let text = ns.substring(from: safe)
        speech.speak(text: text, baseOffset: safe, config: config)
        menuVisible = false
        panel = .none
    }

    private func advanceSpeechChapter() {
        guard speech.spokenRange != nil else { return }
        if engine.current.chapter + 1 < engine.chapterCount {
            engine.jump(toChapter: engine.current.chapter + 1)
            controller?.syncToEngine(animated: false)
            handlePageChanged()
            startSpeaking()
        } else {
            speech.stop()
            showToast("全书朗读完毕")
        }
    }

    /// 朗读越过当前页就自动翻页
    private func followSpeech(offset: Int) {
        let layout = engine.layout(for: engine.current.chapter)
        let page = layout.page(forContentOffset: offset)
        guard page != engine.current.page else { return }
        if page == engine.current.page + 1 {
            controller?.turn(next: true)
        } else {
            engine.setCurrent(PageRef(chapter: engine.current.chapter, page: page))
            controller?.syncToEngine(animated: false)
        }
    }

    private func startAutoRead() {
        speech.stop()
        autoRead.speed = config.autoReadSpeed
        autoRead.start(mode: config.autoReadMode)
        menuVisible = false
        panel = .none
        showToast(config.autoReadMode == .pageByPage ? "自动翻页已开启" : "自动滚动已开启")
    }

    private func speakFrom(_ payload: SelectionPayload) {
        engine.jump(toChapter: payload.chapter, offset: payload.range.location)
        controller?.syncToEngine(animated: false)
        clearSelection()
        startSpeaking()
    }

    // MARK: - 提示

    private func showToast(_ text: String) {
        toast = text
        Task {
            try? await Task.sleep(nanoseconds: 1_600_000_000)
            if toast == text { toast = nil }
        }
    }
}
