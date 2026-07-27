import Foundation

/// 内置样例书库。当前版本不接入任何书源：
/// 三本为完整撰写的样例小说，其余为书城陈列用的样例书（正文由模板生成，并在章末注明）。
/// 接入真实书源时，只需替换 `books` 的数据来源，上层（分页、阅读、进度）无需改动。
enum SampleLibrary {

    static let books: [Book] = {
        var result: [Book] = [
            makeBook(id: "shanhai", title: "山海归墟", author: "白鹭洲",
                     intro: "东海边的少年阿墟能听见海底的呼吸声。父亲失踪二十三年后，他拿到了半块刻着「归墟」的木牌，带着一条不认路的船、一个不问原因的伙计，和一枚生锈的铜哨，潜入了没有底的海。",
                     category: "玄幻", tags: ["东方玄幻", "海洋", "寻亲"],
                     finished: false, rating: 9.2, readers: 183_000,
                     colors: ["#2E5C6E", "#17313B"], glyph: "墟",
                     chapters: ShanhaiText.chapters),
            makeBook(id: "changye", title: "长夜巡游", author: "沉舟侧",
                     intro: "夜班出租车司机陈叙发现，每年秋天总有七个晚上，会有乘客要去一个地图上不存在的地址——松林路 47 号。那栋楼在 1998 年的一场大火里烧掉了。",
                     category: "悬疑", tags: ["都市怪谈", "温情", "单元剧"],
                     finished: true, rating: 9.5, readers: 264_000,
                     colors: ["#3A3F55", "#1B1D2A"], glyph: "夜",
                     chapters: ChangyeText.chapters),
            makeBook(id: "xingjian", title: "星舰拾荒者", author: "第七类接触",
                     intro: "拾荒者林筏在三号废船带捡到一艘失踪十七年的深空勘探船。船上七个人还活着，休眠舱还剩四十六小时能源——而唤醒他们所需的电量，恰好等于把真相广播回地球所需的电量。",
                     category: "科幻", tags: ["硬科幻", "太空", "抉择"],
                     finished: true, rating: 9.6, readers: 141_000,
                     colors: ["#1F4B60", "#0C1E2C"], glyph: "星",
                     chapters: XingjianText.chapters)
        ]
        result.append(contentsOf: fillerBooks)
        return result
    }()

    private static func makeBook(id: String, title: String, author: String, intro: String,
                                 category: String, tags: [String], finished: Bool,
                                 rating: Double, readers: Int, colors: [String], glyph: String,
                                 chapters: [(String, String)]) -> Book {
        let list = chapters.enumerated().map { idx, item in
            Chapter(id: "\(id)-\(idx)", index: idx, title: item.0,
                    content: normalize(item.1))
        }
        return Book(id: id, title: title, author: author, intro: intro, category: category,
                    tags: tags, isFinished: finished, rating: rating, readerCount: readers,
                    coverColors: colors, coverGlyph: glyph, chapters: list)
    }

    /// 统一正文格式：去掉空行与段首空格，保证「字符偏移 == 原文偏移」
    private static func normalize(_ raw: String) -> String {
        raw.split(separator: "\n", omittingEmptySubsequences: true)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .joined(separator: "\n")
    }

    // MARK: - 书城陈列用样例书

    private struct FillerSeed {
        let id: String, title: String, author: String, intro: String
        let category: String, tags: [String]
        let colors: [String], glyph: String
        let rating: Double, readers: Int, finished: Bool
        /// 用于生成正文的主题词：主角、地点、目标物、对手
        let motifs: [String]
    }

    private static let fillerSeeds: [FillerSeed] = [
        FillerSeed(id: "qingming", title: "清明上河图密档", author: "汴京客",
                   intro: "一幅传世名画里藏着北宋末年的最后一封密信，修复师沿着画中八百一十二个人物，一个个找了下去。",
                   category: "历史", tags: ["考据", "悬疑"], colors: ["#8A6A3F", "#4A3521"], glyph: "汴",
                   rating: 8.9, readers: 97_000, finished: false,
                   motifs: ["修复师苏怀", "汴京", "那封密信", "画院总管"]),
        FillerSeed(id: "shanhuo", title: "山火之后", author: "林间有雾",
                   intro: "护林员在烧过的山上守了三年，等一棵树重新发芽，也等一个人回来。",
                   category: "现实", tags: ["治愈", "自然"], colors: ["#4E7A4C", "#22402A"], glyph: "山",
                   rating: 9.1, readers: 62_000, finished: true,
                   motifs: ["护林员老段", "北坡", "那棵冷杉", "山下的开发商"]),
        FillerSeed(id: "wuyan", title: "无言之城", author: "拾陆",
                   intro: "一夜之间，全城的人都失去了说话的能力。第二天早上，只有一个人还能开口。",
                   category: "科幻", tags: ["软科幻", "群像"], colors: ["#4A4F63", "#22252F"], glyph: "言",
                   rating: 8.7, readers: 118_000, finished: false,
                   motifs: ["电台主播周迢", "旧城区", "那段广播", "沉默委员会"]),
        FillerSeed(id: "jianghu", title: "刀客不问江湖", author: "落雁生",
                   intro: "他把刀埋在客栈后院第七棵槐树下，说这辈子不再拔出来。然后有人带着他师父的骨灰上门了。",
                   category: "武侠", tags: ["江湖", "热血"], colors: ["#7A3B32", "#3A1A17"], glyph: "刀",
                   rating: 9.0, readers: 205_000, finished: false,
                   motifs: ["刀客薛让", "落雁镇", "那把断刀", "漕帮"]),
        FillerSeed(id: "shizhong", title: "时钟修理店", author: "槐序",
                   intro: "巷子深处那家钟表店只在雨天开门，修的不是钟，是别人过不去的那一天。",
                   category: "奇幻", tags: ["治愈", "短篇集"], colors: ["#6A5A8A", "#332B47"], glyph: "钟",
                   rating: 9.3, readers: 156_000, finished: true,
                   motifs: ["钟表匠沈迟", "长乐巷", "那只怀表", "总来退货的客人"]),
        FillerSeed(id: "haishang", title: "海上无航线", author: "北纬三十",
                   intro: "一艘货轮偏离航线四百海里，船长坚持说仪器没坏——他在追一个只有他能看见的信号。",
                   category: "悬疑", tags: ["海洋", "心理"], colors: ["#2C5A72", "#12303F"], glyph: "航",
                   rating: 8.6, readers: 88_000, finished: false,
                   motifs: ["大副何期", "太平洋中段", "那段信号", "船东"]),
        FillerSeed(id: "chengnan", title: "城南旧书铺", author: "青衫渡",
                   intro: "旧书铺收书从不看内容，只看扉页有没有字。写了字的书，都有故事要还。",
                   category: "现实", tags: ["温情", "短篇集"], colors: ["#8A7350", "#463A26"], glyph: "书",
                   rating: 9.4, readers: 133_000, finished: true,
                   motifs: ["店主余复", "城南", "那本旧书", "拆迁办"]),
        FillerSeed(id: "jixue", title: "极雪纪年", author: "白夜行者",
                   intro: "科考站失联的第九十天，最后一个队员开始在日志上写下不属于这个纪年的日期。",
                   category: "科幻", tags: ["极地", "悬疑"], colors: ["#5A7A93", "#28414F"], glyph: "雪",
                   rating: 8.8, readers: 74_000, finished: false,
                   motifs: ["队医程霜", "南极站", "那本日志", "总部"]),
        FillerSeed(id: "guixu", title: "长安十二时辰后", author: "朱雀街",
                   intro: "上元夜过去之后，长安城多出了一条不该存在的街，只有醉过的人能走进去。",
                   category: "历史", tags: ["唐风", "奇谈"], colors: ["#9A4B3B", "#4C221B"], glyph: "唐",
                   rating: 9.0, readers: 172_000, finished: false,
                   motifs: ["不良人裴七", "长安", "那条街", "京兆府"])
    ]

    private static var fillerBooks: [Book] {
        fillerSeeds.map { seed in
            let chapters = (0..<10).map { idx in
                Chapter(id: "\(seed.id)-\(idx)", index: idx,
                        title: chapterTitle(index: idx, seed: seed),
                        content: normalize(fillerContent(index: idx, seed: seed)))
            }
            return Book(id: seed.id, title: seed.title, author: seed.author, intro: seed.intro,
                        category: seed.category, tags: seed.tags, isFinished: seed.finished,
                        rating: seed.rating, readerCount: seed.readers,
                        coverColors: seed.colors, coverGlyph: seed.glyph, chapters: chapters)
        }
    }

    private static func chapterTitle(index: Int, seed: FillerSeed) -> String {
        let names = ["开端", "旧痕", "来客", "夜谈", "分岔", "试探", "回声", "追索", "对峙", "余温"]
        return "第\(chineseNumber(index + 1))章 \(names[index % names.count])"
    }

    private static func chineseNumber(_ n: Int) -> String {
        let digits = ["零", "一", "二", "三", "四", "五", "六", "七", "八", "九"]
        if n < 10 { return digits[n] }
        if n < 20 { return n == 10 ? "十" : "十" + digits[n % 10] }
        let tens = digits[n / 10] + "十"
        return n % 10 == 0 ? tens : tens + digits[n % 10]
    }

    private static func fillerContent(index: Int, seed: FillerSeed) -> String {
        let who = seed.motifs[0], place = seed.motifs[1]
        let thing = seed.motifs[2], foe = seed.motifs[3]
        let hours = ["天刚亮", "正午", "傍晚", "入夜以后", "后半夜"][index % 5]
        let weather = ["起了雾", "下着小雨", "风很大", "格外闷热", "落了一层薄霜"][(index + 2) % 5]

        return """
        \(hours)的时候，\(place)\(weather)。
        \(who)在门口站了很久才推门进去。这半个月他已经把该问的人都问过一遍，得到的答案彼此矛盾，唯一一致的是：所有人提到\(thing)时，都会先停顿一下。
        停顿是有意义的。他做这行十几年，早就明白一个道理——人在撒谎前会想措辞，在说真话前会想后果。前者停顿短，后者停顿长。
        这些人的停顿，都很长。
        屋里比外面暗。桌上摊着几张纸，压着一只豁了口的碗。\(who)把纸抽出来看了一遍，纸上写的东西和他手里那份对不上，差的不是内容，是日期。
        "你早就知道了。"他说。
        对面的人没有否认，只是把碗端起来喝了一口，喉结上下动了一次。
        "知道又能怎么样。"那人说，"\(foe)的人昨天来过，问的和你一样。我给他们看了同一张纸。"
        "他们信了？"
        "他们不需要信。"那人把碗放下，"他们只需要有人签字。"
        \(who)沉默了一会儿。窗外传来一阵脚步声，走过去了，又折回来，在门口停住。他没有回头。
        "\(thing)现在在哪儿？"
        "在它该在的地方。"
        "那是哪儿？"
        那人笑了一下，笑得很累。
        "在你已经去过、但没有细看的那个地方。"
        \(who)站起身，把纸折好塞进怀里。走到门口时他停了一下，没有回头。
        "还有一件事。"他说，"当年那件事，你要是早说一句，很多人不至于。"
        "我说过。"那人的声音低下去，"说了三次。第一次没人听，第二次没人敢听，第三次——"
        "第三次怎么样？"
        "第三次，听的人第二天就不在了。"
        门开了又关。外面的\(weather)还没停。
        \(who)沿着来时的路往回走，走到一半忽然明白了那句话的意思。他确实去过那个地方，就在三天前，还在那儿站了将近一个钟头。
        他当时看见了，只是没有细看。
        脚步加快了。
        （样例内容，用于演示排版与阅读功能）
        """
    }
}
