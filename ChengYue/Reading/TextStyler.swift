import UIKit
import CoreText

/// 把章节正文变成可排版的富文本。
/// 约定：`content` 已规范化（段落之间只有一个 `\n`，段首没有空格），
/// 首行缩进交给 NSParagraphStyle，这样「字符偏移」始终等于原文偏移，
/// 换字号、换字体、简繁切换都不会让书签和划线错位。
enum TextStyler {

    struct Built {
        let attributed: NSAttributedString
        /// 正文在富文本中的起始位置（标题块的长度）
        let bodyStart: Int
    }

    static func build(chapter: Chapter, config: ReaderConfig, theme: ReadingTheme) -> Built {
        let body = config.traditionalChinese ? convertToTraditional(chapter.content) : chapter.content
        let title = config.traditionalChinese ? convertToTraditional(chapter.title) : chapter.title

        let result = NSMutableAttributedString()

        // 标题块
        let titleStyle = NSMutableParagraphStyle()
        titleStyle.lineHeightMultiple = 1.25
        titleStyle.paragraphSpacing = config.fontSize * 1.35
        titleStyle.alignment = .natural
        titleStyle.lineBreakMode = .byWordWrapping
        let titleFont = config.fontFamily.uiFont(size: config.fontSize * 1.28, bold: true)
        let titleBlock = NSAttributedString(string: title + "\n", attributes: [
            .font: titleFont,
            .foregroundColor: theme.uiText,
            .paragraphStyle: titleStyle,
            .kern: config.letterSpacing
        ])
        result.append(titleBlock)

        // 正文
        let bodyFont = config.fontFamily.uiFont(size: config.fontSize, bold: config.boldText)
        let para = NSMutableParagraphStyle()
        para.lineHeightMultiple = config.lineHeightMultiple
        para.paragraphSpacing = config.paragraphSpacing
        para.alignment = config.justified ? .justified : .natural
        para.lineBreakMode = .byWordWrapping
        // 中文断行策略：允许在标点处压缩，避免行尾出现孤立标点
        para.hyphenationFactor = 0
        if config.indentFirstLine {
            para.firstLineHeadIndent = config.fontSize * 2
        }

        let bodyBlock = NSAttributedString(string: body, attributes: [
            .font: bodyFont,
            .foregroundColor: theme.uiText,
            .paragraphStyle: para,
            .kern: config.letterSpacing
        ])
        result.append(bodyBlock)

        return Built(attributed: result, bodyStart: (titleBlock.string as NSString).length)
    }

    static func convertToTraditional(_ s: String) -> String {
        s.applyingTransform(StringTransform(rawValue: "Simplified-Traditional"), reverse: false) ?? s
    }
}

/// CoreText 真分页：一页一页地量，得到每页对应的字符范围。
/// 用的是渲染时同一套 framesetter 逻辑，所以「量出来的」和「画出来的」严格一致，
/// 不会出现最后一行被裁掉半截的情况。
enum Paginator {

    static func paginate(_ attributed: NSAttributedString, size: CGSize) -> [NSRange] {
        let length = attributed.length
        guard length > 0, size.width > 20, size.height > 20 else {
            return [NSRange(location: 0, length: length)]
        }

        let framesetter = CTFramesetterCreateWithAttributedString(attributed)
        let path = CGPath(rect: CGRect(origin: .zero, size: size), transform: nil)

        var pages: [NSRange] = []
        var start = 0
        var guardCounter = 0

        while start < length {
            guardCounter += 1
            if guardCounter > 5000 { break }   // 兜底：极端排版参数下不至于死循环

            let frame = CTFramesetterCreateFrame(framesetter, CFRangeMake(start, 0), path, nil)
            let visible = CTFrameGetVisibleStringRange(frame)
            var consumed = visible.length
            if consumed <= 0 {
                // 一个字都放不下（字号过大 / 高度过小），强制吞一个字符防止卡死
                consumed = 1
            }
            let range = NSRange(location: start, length: min(consumed, length - start))
            pages.append(range)
            start = range.location + range.length
        }

        return pages.isEmpty ? [NSRange(location: 0, length: length)] : pages
    }

    /// 连续滚动模式需要整章高度
    static func height(_ attributed: NSAttributedString, width: CGFloat) -> CGFloat {
        guard attributed.length > 0, width > 20 else { return 0 }
        let framesetter = CTFramesetterCreateWithAttributedString(attributed)
        let constraints = CGSize(width: width, height: .greatestFiniteMagnitude)
        var fitRange = CFRange()
        let size = CTFramesetterSuggestFrameSizeWithConstraints(
            framesetter, CFRangeMake(0, 0), nil, constraints, &fitRange
        )
        return ceil(size.height) + 8
    }
}
