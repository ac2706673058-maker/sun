import SwiftUI
import AVFoundation

// MARK: - 面板通用外壳

struct PanelShell<Content: View>: View {
    let theme: ReadingTheme
    var maxHeight: CGFloat = 330
    @ViewBuilder var content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                content
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 16)
        }
        .frame(maxHeight: maxHeight)
        .background(theme.surface)
        .overlay(alignment: .top) { Rectangle().fill(theme.separator).frame(height: 0.5) }
    }
}

struct PanelRow<Content: View>: View {
    let title: String
    let theme: ReadingTheme
    @ViewBuilder var content: Content

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.system(size: 13))
                .foregroundStyle(theme.surfaceMuted)
                .frame(width: 52, alignment: .leading)
            content
        }
    }
}

/// 分段选择器（比系统 Picker 更贴合阅读器的密度）
struct ChipPicker<T: Hashable>: View {
    let items: [T]
    let label: (T) -> String
    @Binding var selection: T
    let theme: ReadingTheme
    var onChange: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: 8) {
            ForEach(items, id: \.self) { item in
                let active = item == selection
                Text(label(item))
                    .font(.system(size: 12, weight: active ? .semibold : .regular))
                    .foregroundStyle(active ? .white : theme.surfaceText)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .background {
                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                            .fill(active ? AnyShapeStyle(Brand.gradient) : AnyShapeStyle(theme.control))
                    }
                    .contentShape(Rectangle())
                    .onTapGesture {
                        selection = item
                        Haptics.tick()
                        onChange?()
                    }
            }
        }
    }
}

struct StepperControl: View {
    let theme: ReadingTheme
    let valueText: String
    var onMinus: () -> Void
    var onPlus: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            button("minus", action: onMinus)
            Text(valueText)
                .font(.system(size: 13, weight: .medium).monospacedDigit())
                .foregroundStyle(theme.surfaceText)
                .frame(minWidth: 52)
            button("plus", action: onPlus)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 4)
        .background(theme.control, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
    }

    private func button(_ icon: String, action: @escaping () -> Void) -> some View {
        Button {
            action()
            Haptics.tick()
        } label: {
            Image(systemName: icon)
                .font(.system(size: 13, weight: .semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
                .contentShape(Rectangle())
        }
        .foregroundStyle(theme.surfaceText)
    }
}

// MARK: - 排版面板

struct TypographyPanel: View {
    @ObservedObject var settings: ReaderSettings
    let theme: ReadingTheme

    var body: some View {
        PanelShell(theme: theme, maxHeight: 360) {
            PanelRow(title: "字号", theme: theme) {
                StepperControl(theme: theme,
                               valueText: "\(Int(settings.config.fontSize))",
                               onMinus: { settings.bumpFontSize(-1) },
                               onPlus: { settings.bumpFontSize(1) })
            }

            PanelRow(title: "行距", theme: theme) {
                ChipPicker(items: [1.35, 1.62, 1.9, 2.2],
                           label: { lineLabel($0) },
                           selection: $settings.config.lineHeightMultiple,
                           theme: theme)
            }

            PanelRow(title: "段距", theme: theme) {
                ChipPicker(items: [CGFloat(4), 13, 22, 32],
                           label: { spacingLabel($0) },
                           selection: $settings.config.paragraphSpacing,
                           theme: theme)
            }

            PanelRow(title: "页边距", theme: theme) {
                ChipPicker(items: [CGFloat(12), 22, 32, 44],
                           label: { marginLabel($0) },
                           selection: $settings.config.horizontalMargin,
                           theme: theme)
            }

            PanelRow(title: "字距", theme: theme) {
                Slider(value: $settings.config.letterSpacing, in: -0.4...2.4, step: 0.2)
                    .tint(Brand.orange)
                Text(String(format: "%.1f", settings.config.letterSpacing))
                    .font(.system(size: 12).monospacedDigit())
                    .foregroundStyle(theme.surfaceMuted)
                    .frame(width: 30)
            }

            PanelRow(title: "字体", theme: theme) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(ReaderFont.allCases) { font in
                            let active = settings.config.fontFamily == font
                            Text(font.name)
                                .font(.system(size: 13, weight: active ? .semibold : .regular))
                                .foregroundStyle(active ? .white : theme.surfaceText)
                                .padding(.horizontal, 14).padding(.vertical, 8)
                                .background {
                                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                                        .fill(active ? AnyShapeStyle(Brand.gradient) : AnyShapeStyle(theme.control))
                                }
                                .onTapGesture {
                                    settings.config.fontFamily = font
                                    Haptics.tick()
                                }
                        }
                    }
                }
            }

            PanelRow(title: "翻页", theme: theme) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(PageAnimation.allCases) { mode in
                            let active = settings.config.pageAnimation == mode
                            Label(mode.name, systemImage: mode.icon)
                                .font(.system(size: 12, weight: active ? .semibold : .regular))
                                .foregroundStyle(active ? .white : theme.surfaceText)
                                .padding(.horizontal, 12).padding(.vertical, 8)
                                .background {
                                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                                        .fill(active ? AnyShapeStyle(Brand.gradient) : AnyShapeStyle(theme.control))
                                }
                                .onTapGesture {
                                    settings.config.pageAnimation = mode
                                    Haptics.tick()
                                }
                        }
                    }
                }
            }

            Divider().overlay(theme.separator)

            HStack(spacing: 18) {
                toggleChip("加粗", isOn: $settings.config.boldText)
                toggleChip("首行缩进", isOn: $settings.config.indentFirstLine)
                toggleChip("两端对齐", isOn: $settings.config.justified)
                toggleChip("繁体", isOn: $settings.config.traditionalChinese)
            }

            Button {
                settings.resetTypography()
                Haptics.success()
            } label: {
                Text("恢复默认排版")
                    .font(.system(size: 12))
                    .foregroundStyle(theme.surfaceMuted)
                    .frame(maxWidth: .infinity)
            }
        }
    }

    private func toggleChip(_ title: String, isOn: Binding<Bool>) -> some View {
        Button {
            isOn.wrappedValue.toggle()
            Haptics.tick()
        } label: {
            HStack(spacing: 5) {
                Image(systemName: isOn.wrappedValue ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 13))
                    .foregroundStyle(isOn.wrappedValue ? Brand.orange : theme.surfaceMuted)
                Text(title).font(.system(size: 12)).foregroundStyle(theme.surfaceText)
            }
        }
    }

    private func lineLabel(_ v: CGFloat) -> String {
        switch v {
        case 1.35: return "紧凑"
        case 1.62: return "标准"
        case 1.9: return "宽松"
        default: return "特宽"
        }
    }

    private func spacingLabel(_ v: CGFloat) -> String {
        switch v {
        case 4: return "无"
        case 13: return "标准"
        case 22: return "较大"
        default: return "很大"
        }
    }

    private func marginLabel(_ v: CGFloat) -> String {
        switch v {
        case 12: return "窄"
        case 22: return "标准"
        case 32: return "宽"
        default: return "特宽"
        }
    }
}

// MARK: - 主题面板

struct ThemePanel: View {
    @ObservedObject var settings: ReaderSettings

    private var theme: ReadingTheme { settings.effectiveTheme }

    var body: some View {
        PanelShell(theme: theme, maxHeight: 280) {
            Text("背景与字色")
                .font(.system(size: 12))
                .foregroundStyle(theme.surfaceMuted)

            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 5), spacing: 14) {
                ForEach(ReadingTheme.all) { t in
                    let active = settings.config.themeID == t.id
                    VStack(spacing: 5) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 9, style: .continuous)
                                .fill(t.backgroundColor)
                                .frame(height: 44)
                                .overlay {
                                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                                        .stroke(active ? Brand.orange : theme.separator, lineWidth: active ? 2 : 1)
                                }
                            Text("阅")
                                .font(.system(size: 16, weight: .medium))
                                .foregroundStyle(t.textColor)
                        }
                        Text(t.name)
                            .font(.system(size: 10))
                            .foregroundStyle(active ? Brand.orange : theme.surfaceMuted)
                    }
                    .onTapGesture {
                        withAnimation(.easeOut(duration: 0.2)) {
                            settings.config.themeID = t.id
                        }
                        Haptics.tick()
                    }
                }
            }

            Divider().overlay(theme.separator)

            Toggle(isOn: $settings.config.followSystemDarkTheme) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("跟随系统深色").font(.system(size: 13)).foregroundStyle(theme.surfaceText)
                    Text("系统切到深色时自动使用夜间主题").font(.system(size: 11)).foregroundStyle(theme.surfaceMuted)
                }
            }
            .tint(Brand.orange)

            if settings.config.followSystemDarkTheme {
                PanelRow(title: "夜间", theme: theme) {
                    ChipPicker(items: ["night", "sepiaN", "oled"],
                               label: { ReadingTheme.theme(id: $0).name },
                               selection: $settings.config.nightThemeID,
                               theme: theme)
                }
            }
        }
    }
}

// MARK: - 亮度面板

struct BrightnessPanel: View {
    @ObservedObject var settings: ReaderSettings
    let theme: ReadingTheme

    var body: some View {
        PanelShell(theme: theme, maxHeight: 220) {
            HStack(spacing: 12) {
                Image(systemName: "sun.min").font(.system(size: 13)).foregroundStyle(theme.surfaceMuted)
                Slider(value: $settings.config.customBrightness, in: 0.02...1)
                    .tint(Brand.orange)
                    .disabled(settings.config.followSystemBrightness)
                    .opacity(settings.config.followSystemBrightness ? 0.4 : 1)
                Image(systemName: "sun.max").font(.system(size: 17)).foregroundStyle(theme.surfaceMuted)
            }

            Toggle(isOn: $settings.config.followSystemBrightness) {
                Text("跟随系统亮度").font(.system(size: 13)).foregroundStyle(theme.surfaceText)
            }
            .tint(Brand.orange)

            Divider().overlay(theme.separator)

            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("暖光滤镜").font(.system(size: 13)).foregroundStyle(theme.surfaceText)
                    Spacer()
                    Text(settings.config.warmFilter < 0.02 ? "关闭" : "\(Int(settings.config.warmFilter * 100))%")
                        .font(.system(size: 12).monospacedDigit())
                        .foregroundStyle(theme.surfaceMuted)
                }
                Slider(value: $settings.config.warmFilter, in: 0...1)
                    .tint(Color(hex: "#FF9B2F"))
                Text("夜里读书时过滤蓝光，比单纯调暗更护眼")
                    .font(.system(size: 11))
                    .foregroundStyle(theme.surfaceMuted)
            }
        }
    }
}

// MARK: - 听书面板

struct SpeechPanel: View {
    @ObservedObject var settings: ReaderSettings
    @ObservedObject var speech: SpeechController
    let theme: ReadingTheme
    var onStart: () -> Void
    var onStop: () -> Void

    private var voices: [AVSpeechSynthesisVoice] { SpeechController.chineseVoices }

    var body: some View {
        PanelShell(theme: theme, maxHeight: 300) {
            HStack(spacing: 20) {
                Button {
                    if speech.isSpeaking { speech.pause() } else { onStart() }
                } label: {
                    Image(systemName: speech.isSpeaking ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 42))
                        .foregroundStyle(Brand.orange)
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(speech.isSpeaking ? "正在朗读" : "点击开始朗读")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(theme.surfaceText)
                    Text(speech.sleepRemaining > 0
                         ? "将在 \(speech.sleepRemaining / 60 + 1) 分钟后停止"
                         : "从当前页继续，读完自动进入下一章")
                        .font(.system(size: 11))
                        .foregroundStyle(theme.surfaceMuted)
                }
                Spacer()
                if speech.isSpeaking {
                    Button("停止") { onStop() }
                        .font(.system(size: 13))
                        .foregroundStyle(theme.surfaceMuted)
                }
            }

            PanelRow(title: "语速", theme: theme) {
                Slider(value: $settings.config.speechRate, in: 0...1)
                    .tint(Brand.orange)
                Text(String(format: "%.1fx", 0.5 + settings.config.speechRate * 1.5))
                    .font(.system(size: 12).monospacedDigit())
                    .foregroundStyle(theme.surfaceMuted)
                    .frame(width: 38)
            }

            PanelRow(title: "音色", theme: theme) {
                Menu {
                    ForEach(voices, id: \.identifier) { v in
                        Button {
                            settings.config.speechVoiceID = v.identifier
                            if speech.isSpeaking { onStart() }
                        } label: {
                            Text(v.name + (v.quality == .enhanced ? "（增强）" : ""))
                        }
                    }
                } label: {
                    HStack {
                        Text(currentVoiceName)
                            .font(.system(size: 13))
                            .foregroundStyle(theme.surfaceText)
                        Spacer()
                        Image(systemName: "chevron.up.chevron.down").font(.system(size: 10))
                            .foregroundStyle(theme.surfaceMuted)
                    }
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(theme.control, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                }
            }

            PanelRow(title: "定时", theme: theme) {
                ChipPicker(items: [0, 15, 30, 60],
                           label: { $0 == 0 ? "不限" : "\($0)分钟" },
                           selection: $speech.sleepTimerMinutes,
                           theme: theme,
                           onChange: { speech.startSleepTimer(minutes: speech.sleepTimerMinutes) })
            }
        }
    }

    private var currentVoiceName: String {
        if let v = voices.first(where: { $0.identifier == settings.config.speechVoiceID }) {
            return v.name
        }
        return voices.first?.name ?? "系统默认"
    }
}

// MARK: - 自动阅读面板

struct AutoReadPanel: View {
    @ObservedObject var settings: ReaderSettings
    @ObservedObject var autoRead: AutoReadController
    let theme: ReadingTheme
    var onStart: () -> Void
    var onStop: () -> Void

    var body: some View {
        PanelShell(theme: theme, maxHeight: 240) {
            PanelRow(title: "方式", theme: theme) {
                ChipPicker(items: AutoReadMode.allCases,
                           label: { $0.name },
                           selection: $settings.config.autoReadMode,
                           theme: theme)
            }

            PanelRow(title: "速度", theme: theme) {
                Slider(value: $settings.config.autoReadSpeed, in: 0...1)
                    .tint(Brand.orange)
                    .onChange(of: settings.config.autoReadSpeed) { _, v in autoRead.speed = v }
                Text("\(Int(settings.config.autoReadSpeed * 100))")
                    .font(.system(size: 12).monospacedDigit())
                    .foregroundStyle(theme.surfaceMuted)
                    .frame(width: 28)
            }

            Text(settings.config.autoReadMode == .pageByPage
                 ? "每隔一段时间自动翻到下一页，读到章末会继续下一章"
                 : "正文匀速向上流动，适合躺着看（需配合「上下滚动」翻页方式）")
                .font(.system(size: 11))
                .foregroundStyle(theme.surfaceMuted)

            Button {
                if autoRead.isRunning { onStop() } else { onStart() }
            } label: {
                Text(autoRead.isRunning ? "停止自动阅读" : "开始自动阅读")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 11)
                    .background(Brand.gradient, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
            }
        }
    }
}

// MARK: - 更多面板

struct MorePanel: View {
    @ObservedObject var settings: ReaderSettings
    let theme: ReadingTheme
    let isBookmarked: Bool
    var onToggleBookmark: () -> Void
    var onCatalog: () -> Void

    var body: some View {
        PanelShell(theme: theme, maxHeight: 340) {
            HStack(spacing: 0) {
                actionItem(isBookmarked ? "取消书签" : "加书签",
                           isBookmarked ? "bookmark.fill" : "bookmark",
                           tint: isBookmarked ? Brand.orange : theme.surfaceText,
                           action: onToggleBookmark)
                actionItem("目录", "list.bullet", tint: theme.surfaceText, action: onCatalog)
                actionItem("自动阅读", "play.circle", tint: theme.surfaceText) { }
            }

            Divider().overlay(theme.separator)

            PanelRow(title: "点击", theme: theme) {
                ChipPicker(items: TapZoneStyle.allCases,
                           label: { $0.name },
                           selection: $settings.config.tapZone,
                           theme: theme)
            }

            toggleRow("音量键翻页", "上下音量键控制上下页", isOn: $settings.config.volumeKeyTurnPage)
            toggleRow("阅读时常亮", "阅读界面不自动锁屏", isOn: $settings.config.keepScreenOn)
            toggleRow("沉浸全屏", "隐藏系统状态栏，用自绘信息栏", isOn: $settings.config.fullScreenMode)
            toggleRow("触感反馈", "翻页与调节时的细微震动", isOn: $settings.config.hapticFeedback)

            Divider().overlay(theme.separator)

            PanelRow(title: "信息栏", theme: theme) {
                HStack(spacing: 16) {
                    miniToggle("页眉", isOn: $settings.config.showHeader)
                    miniToggle("页脚", isOn: $settings.config.showFooter)
                    miniToggle("电量", isOn: $settings.config.showBattery)
                    miniToggle("时间", isOn: $settings.config.showClock)
                    miniToggle("进度", isOn: $settings.config.showPagePercent)
                }
            }

            PanelRow(title: "护眼", theme: theme) {
                ChipPicker(items: [0, 30, 45, 60],
                           label: { $0 == 0 ? "关闭" : "\($0)分钟" },
                           selection: $settings.config.eyeRestMinutes,
                           theme: theme)
            }
        }
    }

    private func actionItem(_ title: String, _ icon: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button {
            Haptics.tap()
            action()
        } label: {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.system(size: 20))
                Text(title).font(.system(size: 11))
            }
            .foregroundStyle(tint)
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
    }

    private func toggleRow(_ title: String, _ subtitle: String, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 13)).foregroundStyle(theme.surfaceText)
                Text(subtitle).font(.system(size: 11)).foregroundStyle(theme.surfaceMuted)
            }
        }
        .tint(Brand.orange)
    }

    private func miniToggle(_ title: String, isOn: Binding<Bool>) -> some View {
        Button {
            isOn.wrappedValue.toggle()
            Haptics.tick()
        } label: {
            Text(title)
                .font(.system(size: 12))
                .foregroundStyle(isOn.wrappedValue ? .white : theme.surfaceMuted)
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background {
                    RoundedRectangle(cornerRadius: 7, style: .continuous)
                        .fill(isOn.wrappedValue ? AnyShapeStyle(Brand.orange) : AnyShapeStyle(theme.control))
                }
        }
    }
}
