import SwiftUI

/// 矢量封面：用书籍自带的渐变色 + 装饰字生成，
/// 不依赖任何图片资源，任何分辨率下都清晰。
struct BookCoverView: View {
    let book: Book
    var width: CGFloat = 104
    var showTitle: Bool = true

    private var height: CGFloat { width * 4 / 3 }

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            LinearGradient(colors: book.coverColors.map { Color(hex: $0) },
                           startPoint: .topLeading, endPoint: .bottomTrailing)

            // 书脊高光
            LinearGradient(colors: [.white.opacity(0.28), .clear, .black.opacity(0.14)],
                           startPoint: .leading, endPoint: .trailing)
                .frame(width: width * 0.16)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(book.coverGlyph)
                .font(.system(size: width * 0.46, weight: .bold, design: .serif))
                .foregroundStyle(.white.opacity(0.16))
                .offset(x: width * 0.28, y: -height * 0.22)

            if showTitle {
                VStack(alignment: .leading, spacing: 2) {
                    Text(book.title)
                        .font(.system(size: width * 0.145, weight: .semibold))
                        .foregroundStyle(.white)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    Text(book.author)
                        .font(.system(size: width * 0.1))
                        .foregroundStyle(.white.opacity(0.72))
                        .lineLimit(1)
                }
                .padding(.horizontal, width * 0.1)
                .padding(.bottom, width * 0.09)
                .shadow(color: .black.opacity(0.25), radius: 3, y: 1)
            }
        }
        .frame(width: width, height: height)
        .clipShape(RoundedRectangle(cornerRadius: width * 0.07, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: width * 0.07, style: .continuous)
                .stroke(Color.black.opacity(0.06), lineWidth: 0.5)
        }
        .shadow(color: .black.opacity(0.14), radius: 6, x: 0, y: 3)
    }
}

/// 进度环，书架封面右下角用
struct ProgressRing: View {
    let progress: Double
    var size: CGFloat = 26

    var body: some View {
        ZStack {
            Circle().fill(Color.black.opacity(0.45))
            Circle()
                .trim(from: 0, to: max(0.02, progress))
                .stroke(Brand.orange, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .padding(2.5)
            Text("\(Int(progress * 100))")
                .font(.system(size: size * 0.32, weight: .semibold).monospacedDigit())
                .foregroundStyle(.white)
        }
        .frame(width: size, height: size)
    }
}
