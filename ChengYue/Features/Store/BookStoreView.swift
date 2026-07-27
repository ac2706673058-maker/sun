import SwiftUI

struct BookStoreView: View {
    @EnvironmentObject private var store: LibraryStore
    @EnvironmentObject private var navigator: Navigator

    @State private var category = "全部"
    @State private var bannerIndex = 0

    private var categories: [String] {
        ["全部"] + Array(Set(store.books.map(\.category))).sorted()
    }

    private var featured: [Book] { Array(store.books.prefix(4)) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    searchEntry
                    banner
                    categoryChips
                    rankSection("热门榜", icon: "flame.fill",
                                books: store.filtered(category: category).sorted { $0.readerCount > $1.readerCount })
                    rankSection("口碑榜", icon: "star.fill",
                                books: store.filtered(category: category).sorted { $0.rating > $1.rating })
                    recommendation
                }
                .padding(.bottom, 24)
            }
            .background(Color(hex: "#F7F7F8").ignoresSafeArea())
            .navigationTitle("书城")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var searchEntry: some View {
        NavigationLink {
            SearchView()
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").font(.system(size: 14))
                Text("搜索书名、作者或关键词")
                    .font(.system(size: 14))
                Spacer()
            }
            .foregroundStyle(.secondary)
            .padding(.horizontal, 14).padding(.vertical, 11)
            .background(Color.white, in: Capsule())
            .padding(.horizontal, 16)
        }
        .buttonStyle(.plain)
        .padding(.top, 8)
    }

    private var banner: some View {
        TabView(selection: $bannerIndex) {
            ForEach(Array(featured.enumerated()), id: \.offset) { idx, book in
                Button { navigator.open(book) } label: {
                    HStack(spacing: 14) {
                        BookCoverView(book: book, width: 74, showTitle: false)
                        VStack(alignment: .leading, spacing: 6) {
                            Text(book.title)
                                .font(.system(size: 17, weight: .bold))
                                .foregroundStyle(.white)
                            Text(book.intro)
                                .font(.system(size: 12))
                                .foregroundStyle(.white.opacity(0.85))
                                .lineLimit(2)
                            Text("\(book.category) · \(book.wordCountText)")
                                .font(.system(size: 11))
                                .foregroundStyle(.white.opacity(0.7))
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(14)
                    .background {
                        LinearGradient(colors: book.coverColors.map { Color(hex: $0) },
                                       startPoint: .topLeading, endPoint: .bottomTrailing)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .padding(.horizontal, 16)
                }
                .buttonStyle(.plain)
                .tag(idx)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .automatic))
        .frame(height: 132)
    }

    private var categoryChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(categories, id: \.self) { c in
                    let active = c == category
                    Text(c)
                        .font(.system(size: 13, weight: active ? .semibold : .regular))
                        .foregroundStyle(active ? .white : .primary)
                        .padding(.horizontal, 14).padding(.vertical, 7)
                        .background {
                            Capsule().fill(active ? AnyShapeStyle(Brand.gradient) : AnyShapeStyle(Color.white))
                        }
                        .onTapGesture {
                            withAnimation(.easeOut(duration: 0.18)) { category = c }
                            Haptics.tick()
                        }
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private func rankSection(_ title: String, icon: String, books: [Book]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 6) {
                Image(systemName: icon).font(.system(size: 13)).foregroundStyle(Brand.orange)
                Text(title).font(.system(size: 16, weight: .semibold))
                Spacer()
                Text("更多").font(.system(size: 12)).foregroundStyle(.secondary)
                Image(systemName: "chevron.right").font(.system(size: 10)).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 16)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 14) {
                    ForEach(Array(books.prefix(8).enumerated()), id: \.element.id) { idx, book in
                        NavigationLink {
                            BookDetailView(book: book)
                        } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                ZStack(alignment: .topLeading) {
                                    BookCoverView(book: book, width: 96)
                                    if idx < 3 {
                                        Text("\(idx + 1)")
                                            .font(.system(size: 11, weight: .bold))
                                            .foregroundStyle(.white)
                                            .frame(width: 20, height: 20)
                                            .background(Brand.orangeDeep, in: RoundedRectangle(cornerRadius: 6))
                                            .padding(5)
                                    }
                                }
                                Text(book.title)
                                    .font(.system(size: 12, weight: .medium))
                                    .foregroundStyle(.primary)
                                    .lineLimit(1)
                                Text(book.popularityText)
                                    .font(.system(size: 10))
                                    .foregroundStyle(.secondary)
                            }
                            .frame(width: 96)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }

    private var recommendation: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("猜你喜欢")
                .font(.system(size: 16, weight: .semibold))
                .padding(.horizontal, 16)
            VStack(spacing: 0) {
                ForEach(store.filtered(category: category).sorted { $0.rating > $1.rating }.dropFirst(2).prefix(6)) { book in
                    NavigationLink { BookDetailView(book: book) } label: {
                        BookRow(book: book)
                    }
                    .buttonStyle(.plain)
                    Divider().padding(.leading, 84)
                }
            }
            .background(Color.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .padding(.horizontal, 16)
        }
    }
}

struct BookRow: View {
    let book: Book

    var body: some View {
        HStack(spacing: 12) {
            BookCoverView(book: book, width: 56, showTitle: false)
            VStack(alignment: .leading, spacing: 5) {
                Text(book.title).font(.system(size: 15, weight: .medium)).lineLimit(1)
                Text(book.intro)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                HStack(spacing: 8) {
                    Text(book.author).font(.system(size: 11)).foregroundStyle(.secondary)
                    tagChip(book.category)
                    if let t = book.tags.first { tagChip(t) }
                    Spacer()
                    Text(String(format: "%.1f分", book.rating))
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(Brand.orangeDeep)
                }
            }
        }
        .padding(12)
        .contentShape(Rectangle())
    }

    private func tagChip(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 10))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(Color.black.opacity(0.045), in: RoundedRectangle(cornerRadius: 4))
    }
}
