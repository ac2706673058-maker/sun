import SwiftUI

@main
struct ChengYueApp: App {
    @StateObject private var store = LibraryStore.shared
    @StateObject private var navigator = Navigator()
    @StateObject private var settings = ReaderSettings.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(navigator)
                .environmentObject(settings)
                .tint(Brand.orange)
        }
    }
}

@MainActor
final class Navigator: ObservableObject {
    @Published var readingBook: Book?
    @Published var tab: Int = 0

    func open(_ book: Book) {
        readingBook = book
    }
}

struct RootView: View {
    @EnvironmentObject private var store: LibraryStore
    @EnvironmentObject private var navigator: Navigator

    var body: some View {
        TabView(selection: $navigator.tab) {
            ShelfView()
                .tabItem { Label("书架", systemImage: "books.vertical") }
                .tag(0)
            BookStoreView()
                .tabItem { Label("书城", systemImage: "sparkles") }
                .tag(1)
            ProfileView()
                .tabItem { Label("我的", systemImage: "person") }
                .tag(2)
        }
        .fullScreenCover(item: $navigator.readingBook) { book in
            ReaderView(book: book)
                .environmentObject(store)
        }
    }
}
