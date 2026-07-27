import SwiftUI

struct ShelfView: View {
    @EnvironmentObject private var store: LibraryStore
    @EnvironmentObject private var navigator: Navigator

    @State private var editing = false
    @State private var selected: Set<String> = []
    @State private var gridMode = true
    @State private var showSearch = false

    private let columns = [GridItem(.adaptive(minimum: 96, maximum: 130), spacing: 16)]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    if let last = store.lastReadBook, !editing {
                        continueCard(last)
                    }

                    if store.shelfBooks.isEmpty {
                        EmptyShelf()
                            .padding(.top, 60)
                    } else if gridMode {
                        LazyVGrid(columns: columns, spacing: 20) {
                            ForEach(store.shelfBooks) { book in
                                gridCell(book)
                            }
                        }
                        .padding(.horizontal, 16)
                    } else {
                        LazyVStack(spacing: 14) {
                            ForEach(store.shelfBooks) { book in
                                listCell(book)
                            }
                        }
                        .padding(.horizontal, 16)
                    }
                }
                .padding(.bottom, editing ? 80 : 16)
            }
            .background(Color(hex: "#F7F7F8").ignoresSafeArea())
            .scrollDismissesKeyboard(.immediately)
            .navigationTitle("书架")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        withAnimation { gridMode.toggle() }
                    } label: {
                        Image(systemName: gridMode ? "list.bullet" : "square.grid.2x2")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    HStack(spacing: 16) {
                        NavigationLink { SearchView() } label: { Image(systemName: "magnifyingglass") }
                        Button(editing ? "完成" : "管理") {
                            withAnimation { editing.toggle(); selected.removeAll() }
                        }
                        .font(.system(size: 15))
                    }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if editing { editBar }
            }
        }
    }

    // MARK: - 继续阅读

    private func continueCard(_ book: Book) -> some View {
        let p = store.readingProgress(for: book.id)
        return Button {
            navigator.open(book)
        } label: {
            HStack(spacing: 14) {
                BookCoverView(book: book, width: 62, showTitle: false)
                VStack(alignment: .leading, spacing: 5) {
                    Text("继续阅读")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(Brand.orange)
                    Text(book.title)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Text(chapterTitle(book, p) + " · 已读 \(Int(p.percent * 100))%")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer()
                Text("继续")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16).padding(.vertical, 8)
                    .background(Brand.gradient, in: Capsule())
            }
            .padding(12)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .shadow(color: .black.opacity(0.05), radius: 10, y: 3)
            .padding(.horizontal, 16)
        }
        .buttonStyle(.plain)
    }

    private func chapterTitle(_ book: Book, _ p: ReadingProgress) -> String {
        book.chapters.indices.contains(p.chapterIndex) ? book.chapters[p.chapterIndex].title : "第一章"
    }

    // MARK: - 单元格

    private func gridCell(_ book: Book) -> some View {
        let progress = store.readingProgress(for: book.id).percent
        let isSelected = selected.contains(book.id)
        return VStack(spacing: 7) {
            ZStack(alignment: .bottomTrailing) {
                BookCoverView(book: book, width: 104)
                if progress > 0.001 && !editing {
                    ProgressRing(progress: progress)
                        .padding(6)
                }
                if editing {
                    Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 20))
                        .foregroundStyle(isSelected ? Brand.orange : .white.opacity(0.9))
                        .padding(6)
                }
                if store.shelf.first(where: { $0.bookID == book.id })?.pinned == true {
                    Image(systemName: "pin.fill")
                        .font(.system(size: 9))
                        .foregroundStyle(.white)
                        .padding(4)
                        .background(Brand.orange, in: Circle())
                        .offset(x: -4, y: -4)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                }
            }
            Text(book.title)
                .font(.system(size: 12))
                .foregroundStyle(.primary)
                .lineLimit(1)
                .frame(width: 104)
        }
        .contentShape(Rectangle())
        .onTapGesture {
            if editing { toggleSelect(book.id) } else { navigator.open(book) }
        }
        .contextMenu {
            contextItems(book)
        }
    }

    private func listCell(_ book: Book) -> some View {
        let p = store.readingProgress(for: book.id)
        return HStack(spacing: 12) {
            if editing {
                Image(systemName: selected.contains(book.id) ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(selected.contains(book.id) ? Brand.orange : .secondary)
            }
            BookCoverView(book: book, width: 54, showTitle: false)
            VStack(alignment: .leading, spacing: 4) {
                Text(book.title).font(.system(size: 15, weight: .medium)).lineLimit(1)
                Text(book.author).font(.system(size: 12)).foregroundStyle(.secondary)
                HStack(spacing: 6) {
                    ProgressView(value: p.percent)
                        .tint(Brand.orange)
                        .frame(width: 90)
                    Text("\(Int(p.percent * 100))%")
                        .font(.system(size: 11).monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
        }
        .padding(10)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .contentShape(Rectangle())
        .onTapGesture {
            if editing { toggleSelect(book.id) } else { navigator.open(book) }
        }
        .contextMenu { contextItems(book) }
    }

    @ViewBuilder
    private func contextItems(_ book: Book) -> some View {
        Button { navigator.open(book) } label: { Label("继续阅读", systemImage: "book") }
        Button { store.togglePin(book.id) } label: {
            Label(store.shelf.first(where: { $0.bookID == book.id })?.pinned == true ? "取消置顶" : "置顶",
                  systemImage: "pin")
        }
        NavigationLink { BookDetailView(book: book) } label: { Label("书籍详情", systemImage: "info.circle") }
        Button(role: .destructive) {
            store.removeFromShelf([book.id])
        } label: { Label("移出书架", systemImage: "trash") }
    }

    private func toggleSelect(_ id: String) {
        if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
        Haptics.tick()
    }

    private var editBar: some View {
        HStack(spacing: 22) {
            Button {
                selected.forEach { store.togglePin($0) }
                selected.removeAll()
            } label: {
                VStack(spacing: 3) {
                    Image(systemName: "pin")
                    Text("置顶").font(.system(size: 11))
                }
            }
            Button {
                selected = Set(store.shelfBooks.map(\.id))
            } label: {
                VStack(spacing: 3) {
                    Image(systemName: "checkmark.circle")
                    Text("全选").font(.system(size: 11))
                }
            }
            Button(role: .destructive) {
                store.removeFromShelf(selected)
                selected.removeAll()
                Haptics.success()
            } label: {
                VStack(spacing: 3) {
                    Image(systemName: "trash")
                    Text("移出").font(.system(size: 11))
                }
            }
            .disabled(selected.isEmpty)
        }
        .font(.system(size: 18))
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(.regularMaterial)
    }
}

struct EmptyShelf: View {
    @EnvironmentObject private var navigator: Navigator

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "books.vertical")
                .font(.system(size: 44, weight: .light))
                .foregroundStyle(.tertiary)
            Text("书架空空如也").font(.system(size: 16, weight: .medium))
            Text("去书城挑一本喜欢的书吧").font(.system(size: 13)).foregroundStyle(.secondary)
            Button("逛逛书城") { navigator.tab = 1 }
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(.white)
                .padding(.horizontal, 22).padding(.vertical, 9)
                .background(Brand.gradient, in: Capsule())
                .padding(.top, 4)
        }
    }
}
