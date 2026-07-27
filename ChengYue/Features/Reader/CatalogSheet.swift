import SwiftUI

struct CatalogSheet: View {
    let book: Book
    @ObservedObject var engine: ReaderEngine
    let theme: ReadingTheme
    var onSelectChapter: (Int) -> Void

    @EnvironmentObject private var store: LibraryStore
    @Environment(\.dismiss) private var dismiss
    @State private var tab = 0
    @State private var reversed = false
    @State private var keyword = ""

    private var chapters: [Chapter] {
        let filtered = keyword.isEmpty ? book.chapters
            : book.chapters.filter { $0.title.localizedCaseInsensitiveContains(keyword) }
        return reversed ? Array(filtered.reversed()) : filtered
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("", selection: $tab) {
                    Text("目录").tag(0)
                    Text("书签").tag(1)
                    Text("想法").tag(2)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.bottom, 8)

                switch tab {
                case 0: catalogList
                case 1: bookmarkList
                default: noteList
                }
            }
            .background(theme.backgroundColor.ignoresSafeArea())
            .navigationTitle(book.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if tab == 0 {
                        Button {
                            withAnimation { reversed.toggle() }
                        } label: {
                            Label(reversed ? "正序" : "倒序",
                                  systemImage: reversed ? "arrow.up" : "arrow.down")
                                .font(.system(size: 13))
                        }
                    }
                }
            }
        }
        .tint(Brand.orange)
    }

    // MARK: - 目录

    private var catalogList: some View {
        ScrollViewReader { proxy in
            List {
                Section {
                    ForEach(chapters) { chapter in
                        chapterRow(chapter)
                            .id(chapter.index)
                    }
                } header: {
                    HStack {
                        Text("共 \(book.chapters.count) 章 · \(book.statusText)")
                        Spacer()
                        Text("已读 \(Int(engine.bookProgress * 100))%")
                    }
                    .font(.system(size: 11))
                    .foregroundStyle(theme.secondaryColor)
                }
            }
            .listStyle(.plain)
            .searchable(text: $keyword, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜索章节")
            .onAppear {
                proxy.scrollTo(engine.current.chapter, anchor: .center)
            }
        }
    }

    private func chapterRow(_ chapter: Chapter) -> some View {
        let isCurrent = chapter.index == engine.current.chapter
        let isRead = chapter.index < engine.current.chapter
        return Button {
            onSelectChapter(chapter.index)
            dismiss()
        } label: {
            HStack(spacing: 10) {
                if isCurrent {
                    RoundedRectangle(cornerRadius: 1.5).fill(Brand.orange).frame(width: 3, height: 15)
                }
                Text(chapter.title)
                    .font(.system(size: 15, weight: isCurrent ? .semibold : .regular))
                    .foregroundStyle(isCurrent ? Brand.orange : (isRead ? theme.secondaryColor : theme.textColor))
                    .lineLimit(1)
                Spacer(minLength: 8)
                Text("\(chapter.charCount)字")
                    .font(.system(size: 11).monospacedDigit())
                    .foregroundStyle(theme.secondaryColor.opacity(0.8))
                if store.bookmarkList(bookID: book.id).contains(where: { $0.chapterIndex == chapter.index }) {
                    Image(systemName: "bookmark.fill")
                        .font(.system(size: 10))
                        .foregroundStyle(Brand.orange)
                }
            }
            .padding(.vertical, 3)
            .contentShape(Rectangle())
        }
        .listRowBackground(theme.backgroundColor)
    }

    // MARK: - 书签

    private var bookmarkList: some View {
        let marks = store.bookmarkList(bookID: book.id)
        return Group {
            if marks.isEmpty {
                EmptyHint(icon: "bookmark", title: "还没有书签",
                          subtitle: "在阅读界面「更多」里可以给当前页加书签", theme: theme)
            } else {
                List {
                    ForEach(marks) { mark in
                        Button {
                            engine.jump(toChapter: mark.chapterIndex, offset: mark.charOffset)
                            onSelectChapter(mark.chapterIndex)
                            dismiss()
                        } label: {
                            VStack(alignment: .leading, spacing: 5) {
                                Text(mark.chapterTitle)
                                    .font(.system(size: 14, weight: .medium))
                                    .foregroundStyle(theme.textColor)
                                Text(mark.excerpt)
                                    .font(.system(size: 12))
                                    .foregroundStyle(theme.secondaryColor)
                                    .lineLimit(2)
                                Text(mark.createdAt.formatted(date: .abbreviated, time: .shortened))
                                    .font(.system(size: 10))
                                    .foregroundStyle(theme.secondaryColor.opacity(0.7))
                            }
                        }
                        .listRowBackground(theme.backgroundColor)
                    }
                    .onDelete { indexSet in
                        indexSet.map { marks[$0].id }.forEach { store.removeBookmark(id: $0) }
                    }
                }
                .listStyle(.plain)
            }
        }
    }

    // MARK: - 想法与划线

    private var noteList: some View {
        let items = store.highlightList(bookID: book.id)
        return Group {
            if items.isEmpty {
                EmptyHint(icon: "highlighter", title: "还没有划线",
                          subtitle: "长按正文即可划线、写想法", theme: theme)
            } else {
                List {
                    ForEach(items) { h in
                        Button {
                            engine.jump(toChapter: h.chapterIndex, offset: h.location)
                            onSelectChapter(h.chapterIndex)
                            dismiss()
                        } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                Text(h.text)
                                    .font(.system(size: 14))
                                    .foregroundStyle(theme.textColor)
                                    .padding(.leading, 8)
                                    .overlay(alignment: .leading) {
                                        RoundedRectangle(cornerRadius: 1.5)
                                            .fill(Color(hex: Highlight.palette[min(h.colorIndex, Highlight.palette.count - 1)]))
                                            .frame(width: 3)
                                    }
                                if h.hasNote {
                                    Text(h.note)
                                        .font(.system(size: 12))
                                        .foregroundStyle(theme.secondaryColor)
                                        .padding(8)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .background(theme.isDark ? Color.white.opacity(0.05) : Color.black.opacity(0.035),
                                                    in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                                }
                                Text((engine.chapter(at: h.chapterIndex)?.title ?? "") + " · "
                                     + h.createdAt.formatted(date: .abbreviated, time: .omitted))
                                    .font(.system(size: 10))
                                    .foregroundStyle(theme.secondaryColor.opacity(0.7))
                            }
                        }
                        .listRowBackground(theme.backgroundColor)
                    }
                    .onDelete { indexSet in
                        indexSet.map { items[$0].id }.forEach { store.removeHighlight(id: $0) }
                    }
                }
                .listStyle(.plain)
            }
        }
    }
}

struct EmptyHint: View {
    let icon: String
    let title: String
    let subtitle: String
    let theme: ReadingTheme

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 34, weight: .light))
                .foregroundStyle(theme.secondaryColor.opacity(0.6))
            Text(title).font(.system(size: 15, weight: .medium)).foregroundStyle(theme.textColor)
            Text(subtitle).font(.system(size: 12)).foregroundStyle(theme.secondaryColor)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(30)
    }
}
