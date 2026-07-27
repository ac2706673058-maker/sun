import SwiftUI

struct BookDetailView: View {
    let book: Book

    @EnvironmentObject private var store: LibraryStore
    @EnvironmentObject private var navigator: Navigator
    @State private var expanded = false

    private var progress: ReadingProgress { store.readingProgress(for: book.id) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header
                stats
                introSection
                catalogPreview
            }
            .padding(.bottom, 110)
        }
        .background(Color(hex: "#F7F7F8").ignoresSafeArea())
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) { bottomBar }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 14) {
            BookCoverView(book: book, width: 96)
            VStack(alignment: .leading, spacing: 7) {
                Text(book.title).font(.system(size: 20, weight: .bold)).lineLimit(2)
                Text(book.author).font(.system(size: 13)).foregroundStyle(.secondary)
                HStack(spacing: 6) {
                    ForEach(book.tags.prefix(3), id: \.self) { tag in
                        Text(tag)
                            .font(.system(size: 10))
                            .padding(.horizontal, 7).padding(.vertical, 3)
                            .background(Brand.orangeSoft, in: Capsule())
                            .foregroundStyle(Brand.orangeDeep)
                    }
                }
                Spacer(minLength: 0)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
    }

    private var stats: some View {
        HStack(spacing: 0) {
            statItem(String(format: "%.1f", book.rating), "评分")
            divider
            statItem(book.wordCountText, "字数")
            divider
            statItem(book.statusText, "状态")
            divider
            statItem("\(book.chapters.count)", "章节")
        }
        .padding(.vertical, 14)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .padding(.horizontal, 16)
    }

    private var divider: some View {
        Rectangle().fill(Color.black.opacity(0.06)).frame(width: 0.5, height: 26)
    }

    private func statItem(_ value: String, _ label: String) -> some View {
        VStack(spacing: 4) {
            Text(value).font(.system(size: 15, weight: .semibold))
            Text(label).font(.system(size: 11)).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    private var introSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("简介").font(.system(size: 16, weight: .semibold))
            Text(book.intro)
                .font(.system(size: 14))
                .foregroundStyle(.primary.opacity(0.85))
                .lineSpacing(5)
                .lineLimit(expanded ? nil : 3)
            Button(expanded ? "收起" : "展开全部") {
                withAnimation(.easeOut(duration: 0.2)) { expanded.toggle() }
            }
            .font(.system(size: 12))
            .foregroundStyle(Brand.orange)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .padding(.horizontal, 16)
    }

    private var catalogPreview: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("目录").font(.system(size: 16, weight: .semibold))
                Spacer()
                Text("共 \(book.chapters.count) 章").font(.system(size: 12)).foregroundStyle(.secondary)
            }
            .padding(.bottom, 10)

            ForEach(book.chapters.prefix(8)) { chapter in
                Button {
                    navigator.open(book)
                } label: {
                    HStack {
                        Text(chapter.title)
                            .font(.system(size: 14))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        Spacer()
                        Text("\(chapter.charCount)字")
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 10)
                    .contentShape(Rectangle())
                }
                if chapter.index < min(7, book.chapters.count - 1) {
                    Divider()
                }
            }
        }
        .padding(14)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .padding(.horizontal, 16)
    }

    private var bottomBar: some View {
        HStack(spacing: 12) {
            Button {
                if store.isOnShelf(book.id) {
                    store.removeFromShelf([book.id])
                } else {
                    store.addToShelf(book.id)
                    Haptics.success()
                }
            } label: {
                VStack(spacing: 3) {
                    Image(systemName: store.isOnShelf(book.id) ? "checkmark.circle.fill" : "plus.circle")
                        .font(.system(size: 19))
                    Text(store.isOnShelf(book.id) ? "在书架" : "加书架")
                        .font(.system(size: 10))
                }
                .foregroundStyle(store.isOnShelf(book.id) ? Brand.orange : .secondary)
                .frame(width: 56)
            }

            Button {
                navigator.open(book)
            } label: {
                Text(progress.percent > 0.001 ? "继续阅读 · \(Int(progress.percent * 100))%" : "开始阅读")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 13)
                    .background(Brand.gradient, in: Capsule())
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.regularMaterial)
    }
}

struct SearchView: View {
    @EnvironmentObject private var store: LibraryStore
    @EnvironmentObject private var navigator: Navigator
    @State private var text = ""
    @State private var history: [String] = []

    private var results: [Book] { store.search(text) }

    var body: some View {
        VStack(spacing: 0) {
            if text.isEmpty {
                suggestions
            } else if results.isEmpty {
                EmptyHint(icon: "magnifyingglass", title: "没有找到相关书籍",
                          subtitle: "换个关键词试试，或去书城逛逛",
                          theme: ReadingTheme.all[1])
            } else {
                List(results) { book in
                    NavigationLink { BookDetailView(book: book) } label: {
                        BookRow(book: book)
                    }
                    .listRowInsets(EdgeInsets())
                }
                .listStyle(.plain)
            }
        }
        .searchable(text: $text, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜索书名、作者、关键词")
        .onSubmit(of: .search) {
            let q = text.trimmingCharacters(in: .whitespaces)
            guard !q.isEmpty, !history.contains(q) else { return }
            history.insert(q, at: 0)
            if history.count > 8 { history.removeLast() }
        }
        .navigationTitle("搜索")
        .navigationBarTitleDisplayMode(.inline)
        .background(Color(hex: "#F7F7F8").ignoresSafeArea())
    }

    private var suggestions: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if !history.isEmpty {
                    section("搜索历史") {
                        FlowChips(items: history) { text = $0 }
                    }
                }
                section("热门搜索") {
                    FlowChips(items: Array(store.books.prefix(8).map(\.title))) { text = $0 }
                }
                section("大家都在读") {
                    VStack(spacing: 0) {
                        ForEach(store.books.sorted { $0.readerCount > $1.readerCount }.prefix(5)) { book in
                            NavigationLink { BookDetailView(book: book) } label: { BookRow(book: book) }
                                .buttonStyle(.plain)
                            Divider().padding(.leading, 84)
                        }
                    }
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
            }
            .padding(16)
        }
    }

    private func section<C: View>(_ title: String, @ViewBuilder content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.system(size: 15, weight: .semibold))
            content()
        }
    }
}

/// 简易流式标签布局
struct FlowChips: View {
    let items: [String]
    var onTap: (String) -> Void

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 74, maximum: 160), spacing: 8)],
                  alignment: .leading, spacing: 8) {
            ForEach(items, id: \.self) { item in
                Text(item)
                    .font(.system(size: 12))
                    .lineLimit(1)
                    .padding(.horizontal, 12).padding(.vertical, 7)
                    .background(Color.white, in: Capsule())
                    .onTapGesture { onTap(item) }
            }
        }
    }
}
