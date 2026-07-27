import SwiftUI

struct ProfileView: View {
    @EnvironmentObject private var store: LibraryStore
    @EnvironmentObject private var navigator: Navigator
    @ObservedObject private var settings = ReaderSettings.shared

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    statsCard
                    weekChart
                    historySection
                    settingsSection
                }
                .padding(.bottom, 24)
            }
            .background(Color(hex: "#F7F7F8").ignoresSafeArea())
            .navigationTitle("我的")
        }
    }

    // MARK: - 时长

    private var statsCard: some View {
        VStack(spacing: 14) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("今日阅读").font(.system(size: 12)).foregroundStyle(.white.opacity(0.8))
                    HStack(alignment: .firstTextBaseline, spacing: 3) {
                        Text("\(store.todaySeconds / 60)")
                            .font(.system(size: 32, weight: .bold).monospacedDigit())
                        Text("分钟").font(.system(size: 13))
                    }
                    .foregroundStyle(.white)
                }
                Spacer()
                VStack(spacing: 3) {
                    Text("连续 \(store.streakDays) 天")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.white)
                    Text("累计 \(store.totalSeconds / 3600) 小时 \((store.totalSeconds % 3600) / 60) 分")
                        .font(.system(size: 11))
                        .foregroundStyle(.white.opacity(0.8))
                }
            }

            let goal = max(1, settings.config.dailyGoalMinutes)
            let done = min(1.0, Double(store.todaySeconds / 60) / Double(goal))
            VStack(alignment: .leading, spacing: 5) {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(.white.opacity(0.25))
                        Capsule().fill(.white).frame(width: max(4, geo.size.width * done))
                    }
                }
                .frame(height: 6)
                HStack {
                    Text("今日目标 \(goal) 分钟").font(.system(size: 11)).foregroundStyle(.white.opacity(0.85))
                    Spacer()
                    Text(done >= 1 ? "已达成 🎉" : "还差 \(goal - store.todaySeconds / 60) 分钟")
                        .font(.system(size: 11))
                        .foregroundStyle(.white.opacity(0.85))
                }
            }
        }
        .padding(16)
        .background(Brand.gradient, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .padding(.horizontal, 16)
    }

    private var weekChart: some View {
        let data = store.weekMinutes
        let maxValue = max(1, data.max() ?? 1)
        return VStack(alignment: .leading, spacing: 12) {
            Text("最近七天").font(.system(size: 15, weight: .semibold))
            HStack(alignment: .bottom, spacing: 10) {
                ForEach(Array(data.enumerated()), id: \.offset) { idx, minutes in
                    VStack(spacing: 6) {
                        Text(minutes > 0 ? "\(minutes)" : "")
                            .font(.system(size: 9).monospacedDigit())
                            .foregroundStyle(.secondary)
                        RoundedRectangle(cornerRadius: 4)
                            .fill(idx == data.count - 1 ? AnyShapeStyle(Brand.gradient) : AnyShapeStyle(Brand.orange.opacity(0.25)))
                            .frame(height: max(4, CGFloat(minutes) / CGFloat(maxValue) * 78))
                        Text(dayLabel(offset: data.count - 1 - idx))
                            .font(.system(size: 10))
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            .frame(height: 118)
        }
        .padding(14)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.horizontal, 16)
    }

    private func dayLabel(offset: Int) -> String {
        if offset == 0 { return "今天" }
        let date = Calendar.current.date(byAdding: .day, value: -offset, to: Date()) ?? Date()
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "E"
        return f.string(from: date)
    }

    private var historySection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("最近浏览").font(.system(size: 15, weight: .semibold)).padding(.horizontal, 16)
            if store.history.isEmpty {
                Text("还没有阅读记录")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 16)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 14) {
                        ForEach(store.history.prefix(10)) { entry in
                            if let book = store.book(id: entry.bookID) {
                                Button { navigator.open(book) } label: {
                                    VStack(alignment: .leading, spacing: 6) {
                                        BookCoverView(book: book, width: 76)
                                        Text(book.title)
                                            .font(.system(size: 11))
                                            .foregroundStyle(.primary)
                                            .lineLimit(1)
                                            .frame(width: 76, alignment: .leading)
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
        }
    }

    private var settingsSection: some View {
        VStack(spacing: 0) {
            NavigationLink { AppSettingsView() } label: {
                settingRow("阅读偏好", "textformat", detail: "字体 · 主题 · 翻页")
            }
            Divider().padding(.leading, 52)
            NavigationLink { ReadingDataView() } label: {
                settingRow("我的划线与书签", "highlighter", detail: "\(store.highlights.count) 条")
            }
            Divider().padding(.leading, 52)
            NavigationLink { AboutView() } label: {
                settingRow("关于橙阅", "info.circle", detail: "v1.0")
            }
        }
        .background(Color.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.horizontal, 16)
    }

    private func settingRow(_ title: String, _ icon: String, detail: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 15))
                .foregroundStyle(Brand.orange)
                .frame(width: 24)
            Text(title).font(.system(size: 15)).foregroundStyle(.primary)
            Spacer()
            Text(detail).font(.system(size: 12)).foregroundStyle(.secondary)
            Image(systemName: "chevron.right").font(.system(size: 11)).foregroundStyle(.tertiary)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 13)
        .contentShape(Rectangle())
    }
}

// MARK: - 阅读偏好

struct AppSettingsView: View {
    @ObservedObject private var settings = ReaderSettings.shared

    var body: some View {
        Form {
            Section("排版") {
                HStack {
                    Text("字号")
                    Spacer()
                    Text("\(Int(settings.config.fontSize))").foregroundStyle(.secondary)
                    Stepper("", value: $settings.config.fontSize, in: 13...30, step: 1).labelsHidden()
                }
                Picker("字体", selection: $settings.config.fontFamily) {
                    ForEach(ReaderFont.allCases) { Text($0.name).tag($0) }
                }
                Picker("翻页方式", selection: $settings.config.pageAnimation) {
                    ForEach(PageAnimation.allCases) { Text($0.name).tag($0) }
                }
                Toggle("首行缩进", isOn: $settings.config.indentFirstLine)
                Toggle("两端对齐", isOn: $settings.config.justified)
                Toggle("繁体显示", isOn: $settings.config.traditionalChinese)
            }

            Section("阅读体验") {
                Picker("点击区域", selection: $settings.config.tapZone) {
                    ForEach(TapZoneStyle.allCases) { Text($0.name).tag($0) }
                }
                Toggle("音量键翻页", isOn: $settings.config.volumeKeyTurnPage)
                Toggle("触感反馈", isOn: $settings.config.hapticFeedback)
                Toggle("阅读时屏幕常亮", isOn: $settings.config.keepScreenOn)
                Toggle("沉浸式全屏", isOn: $settings.config.fullScreenMode)
                Toggle("跟随系统深色", isOn: $settings.config.followSystemDarkTheme)
            }

            Section("目标与关怀") {
                Picker("每日目标", selection: $settings.config.dailyGoalMinutes) {
                    ForEach([15, 30, 45, 60, 90], id: \.self) { Text("\($0) 分钟").tag($0) }
                }
                Picker("护眼提醒", selection: $settings.config.eyeRestMinutes) {
                    Text("关闭").tag(0)
                    ForEach([30, 45, 60], id: \.self) { Text("每 \($0) 分钟").tag($0) }
                }
            }
        }
        .navigationTitle("阅读偏好")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - 划线与书签汇总

struct ReadingDataView: View {
    @EnvironmentObject private var store: LibraryStore

    var body: some View {
        List {
            if store.highlights.isEmpty && store.bookmarks.isEmpty {
                Text("还没有划线或书签").foregroundStyle(.secondary)
            }
            ForEach(bookIDs, id: \.self) { id in
                if let book = store.book(id: id) {
                    Section(book.title) {
                        ForEach(store.highlightList(bookID: id)) { h in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(h.text).font(.system(size: 14)).lineLimit(3)
                                if h.hasNote {
                                    Text(h.note).font(.system(size: 12)).foregroundStyle(.secondary)
                                }
                            }
                        }
                        ForEach(store.bookmarkList(bookID: id)) { m in
                            HStack {
                                Image(systemName: "bookmark.fill")
                                    .font(.system(size: 11))
                                    .foregroundStyle(Brand.orange)
                                Text(m.chapterTitle).font(.system(size: 14))
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("划线与书签")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var bookIDs: [String] {
        Array(Set(store.highlights.map(\.bookID) + store.bookmarks.map(\.bookID))).sorted()
    }
}

struct AboutView: View {
    var body: some View {
        VStack(spacing: 14) {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(Brand.gradient)
                .frame(width: 88, height: 88)
                .overlay {
                    Text("橙")
                        .font(.system(size: 42, weight: .bold, design: .serif))
                        .foregroundStyle(.white)
                }
            Text("橙阅").font(.system(size: 20, weight: .bold))
            Text("v1.0 · 本地样例书库").font(.system(size: 12)).foregroundStyle(.secondary)
            Text("一个把阅读体验做到极致的小说阅读器。\n当前版本未接入任何书源，书库为内置样例内容。")
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Spacer()
        }
        .padding(.top, 40)
        .frame(maxWidth: .infinity)
        .background(Color(hex: "#F7F7F8").ignoresSafeArea())
        .navigationTitle("关于")
        .navigationBarTitleDisplayMode(.inline)
    }
}
