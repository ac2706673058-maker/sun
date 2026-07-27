#!/usr/bin/env python3
"""把 Swift 里的样例书正文抽出来，生成网页版用的 books.json，并注入 app 模板。"""
import re, json, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "ChengYue" / "Content"

def curly(line):
    # 成对的 ASCII 双引号转成中文引号，读起来才像正经排版
    out, open_q = [], True
    for chpr in line:
        if chpr == '"':
            out.append('\u300c' if open_q else '\u300d')
            open_q = not open_q
        else:
            out.append(chpr)
    return ''.join(out)

def parse_swift(path):
    s = (SRC / path).read_text()
    # 章节标题顺序
    m = re.search(r'static let chapters: \[\(String, String\)\] = \[(.*?)\n    \]', s, re.S)
    entries = re.findall(r'\("([^"]+)",\s*(c\d+)\)', m.group(1))
    bodies = dict(re.findall(r'static let (c\d+) = """\n(.*?)\n"""', s, re.S))
    out = []
    for title, key in entries:
        raw = bodies[key]
        lines = [ln.strip() for ln in raw.split("\n")]
        out.append({"title": title, "text": "\n".join([curly(l) for l in lines if l])})
    return out

FILLER = [
    ("qingming","清明上河图密档","汴京客","一幅传世名画里藏着北宋末年的最后一封密信，修复师沿着画中八百一十二个人物，一个个找了下去。","历史",["考据","悬疑"],["#8A6A3F","#4A3521"],"汴",8.9,97000,False,["修复师苏怀","汴京","那封密信","画院总管"]),
    ("shanhuo","山火之后","林间有雾","护林员在烧过的山上守了三年，等一棵树重新发芽，也等一个人回来。","现实",["治愈","自然"],["#4E7A4C","#22402A"],"山",9.1,62000,True,["护林员老段","北坡","那棵冷杉","山下的开发商"]),
    ("wuyan","无言之城","拾陆","一夜之间，全城的人都失去了说话的能力。第二天早上，只有一个人还能开口。","科幻",["软科幻","群像"],["#4A4F63","#22252F"],"言",8.7,118000,False,["电台主播周迢","旧城区","那段广播","沉默委员会"]),
    ("jianghu","刀客不问江湖","落雁生","他把刀埋在客栈后院第七棵槐树下，说这辈子不再拔出来。然后有人带着他师父的骨灰上门了。","武侠",["江湖","热血"],["#7A3B32","#3A1A17"],"刀",9.0,205000,False,["刀客薛让","落雁镇","那把断刀","漕帮"]),
    ("shizhong","时钟修理店","槐序","巷子深处那家钟表店只在雨天开门，修的不是钟，是别人过不去的那一天。","奇幻",["治愈","短篇集"],["#6A5A8A","#332B47"],"钟",9.3,156000,True,["钟表匠沈迟","长乐巷","那只怀表","总来退货的客人"]),
    ("haishang","海上无航线","北纬三十","一艘货轮偏离航线四百海里，船长坚持说仪器没坏——他在追一个只有他能看见的信号。","悬疑",["海洋","心理"],["#2C5A72","#12303F"],"航",8.6,88000,False,["大副何期","太平洋中段","那段信号","船东"]),
    ("chengnan","城南旧书铺","青衫渡","旧书铺收书从不看内容，只看扉页有没有字。写了字的书，都有故事要还。","现实",["温情","短篇集"],["#8A7350","#463A26"],"书",9.4,133000,True,["店主余复","城南","那本旧书","拆迁办"]),
    ("jixue","极雪纪年","白夜行者","科考站失联的第九十天，最后一个队员开始在日志上写下不属于这个纪年的日期。","科幻",["极地","悬疑"],["#5A7A93","#28414F"],"雪",8.8,74000,False,["队医程霜","南极站","那本日志","总部"]),
    ("guixu","长安十二时辰后","朱雀街","上元夜过去之后，长安城多出了一条不该存在的街，只有醉过的人能走进去。","历史",["唐风","奇谈"],["#9A4B3B","#4C221B"],"唐",9.0,172000,False,["不良人裴七","长安","那条街","京兆府"]),
]

CN = ["零","一","二","三","四","五","六","七","八","九"]
def cn_num(n):
    if n < 10: return CN[n]
    if n < 20: return "十" if n == 10 else "十" + CN[n % 10]
    t = CN[n // 10] + "十"
    return t if n % 10 == 0 else t + CN[n % 10]

NAMES = ["开端","旧痕","来客","夜谈","分岔","试探","回声","追索","对峙","余温"]
HOURS = ["天刚亮","正午","傍晚","入夜以后","后半夜"]
WEATHER = ["起了雾","下着小雨","风很大","格外闷热","落了一层薄霜"]

def filler_text(i, motifs):
    who, place, thing, foe = motifs
    hour, weather = HOURS[i % 5], WEATHER[(i + 2) % 5]
    return "\n".join([
        f"{hour}的时候，{place}{weather}。",
        f"{who}在门口站了很久才推门进去。这半个月他已经把该问的人都问过一遍，得到的答案彼此矛盾，唯一一致的是：所有人提到{thing}时，都会先停顿一下。",
        "停顿是有意义的。他做这行十几年，早就明白一个道理——人在撒谎前会想措辞，在说真话前会想后果。前者停顿短，后者停顿长。",
        "这些人的停顿，都很长。",
        "屋里比外面暗。桌上摊着几张纸，压着一只豁了口的碗。" + who + "把纸抽出来看了一遍，纸上写的东西和他手里那份对不上，差的不是内容，是日期。",
        "「你早就知道了。」他说。",
        "对面的人没有否认，只是把碗端起来喝了一口，喉结上下动了一次。",
        f"「知道又能怎么样。」那人说，「{foe}的人昨天来过，问的和你一样。我给他们看了同一张纸。」",
        "「他们信了？」",
        "「他们不需要信。」那人把碗放下，「他们只需要有人签字。」",
        f"{who}沉默了一会儿。窗外传来一阵脚步声，走过去了，又折回来，在门口停住。他没有回头。",
        f"「{thing}现在在哪儿？」",
        "「在它该在的地方。」",
        "「那是哪儿？」",
        "那人笑了一下，笑得很累。",
        "「在你已经去过、但没有细看的那个地方。」",
        f"{who}站起身，把纸折好塞进怀里。走到门口时他停了一下，没有回头。",
        "「还有一件事。」他说，「当年那件事，你要是早说一句，很多人不至于。」",
        "「我说过。」那人的声音低下去，「说了三次。第一次没人听，第二次没人敢听，第三次——」",
        "「第三次怎么样？」",
        "「第三次，听的人第二天就不在了。」",
        f"门开了又关。外面的{weather}还没停。",
        f"{who}沿着来时的路往回走，走到一半忽然明白了那句话的意思。他确实去过那个地方，就在三天前，还在那儿站了将近一个钟头。",
        "他当时看见了，只是没有细看。",
        "脚步加快了。",
        "（样例内容，用于演示排版与阅读功能）",
    ])

books = [
    {"id":"shanhai","title":"山海归墟","author":"白鹭洲",
     "intro":"东海边的少年阿墟能听见海底的呼吸声。父亲失踪二十三年后，他拿到了半块刻着「归墟」的木牌，带着一条不认路的船、一个不问原因的伙计，和一枚生锈的铜哨，潜入了没有底的海。",
     "category":"玄幻","tags":["东方玄幻","海洋","寻亲"],"colors":["#2E5C6E","#17313B"],"glyph":"墟",
     "rating":9.2,"readers":183000,"finished":False,"chapters":parse_swift("BookTextsShanhai.swift")},
    {"id":"changye","title":"长夜巡游","author":"沉舟侧",
     "intro":"夜班出租车司机陈叙发现，每年秋天总有七个晚上，会有乘客要去一个地图上不存在的地址——松林路 47 号。那栋楼在 1998 年的一场大火里烧掉了。",
     "category":"悬疑","tags":["都市怪谈","温情","单元剧"],"colors":["#3A3F55","#1B1D2A"],"glyph":"夜",
     "rating":9.5,"readers":264000,"finished":True,"chapters":parse_swift("BookTextsChangye.swift")},
    {"id":"xingjian","title":"星舰拾荒者","author":"第七类接触",
     "intro":"拾荒者林筏在三号废船带捡到一艘失踪十七年的深空勘探船。船上七个人还活着，休眠舱还剩四十六小时能源——而唤醒他们所需的电量，恰好等于把真相广播回地球所需的电量。",
     "category":"科幻","tags":["硬科幻","太空","抉择"],"colors":["#1F4B60","#0C1E2C"],"glyph":"星",
     "rating":9.6,"readers":141000,"finished":True,"chapters":parse_swift("BookTextsXingjian.swift")},
]

for bid,title,author,intro,cat,tags,colors,glyph,rating,readers,fin,motifs in FILLER:
    books.append({"id":bid,"title":title,"author":author,"intro":intro,"category":cat,"tags":tags,
                  "colors":colors,"glyph":glyph,"rating":rating,"readers":readers,"finished":fin,
                  "chapters":[{"title":f"第{cn_num(i+1)}章 {NAMES[i%len(NAMES)]}","text":filler_text(i,motifs)}
                              for i in range(10)]})

data = json.dumps(books, ensure_ascii=False, separators=(",",":"))
tpl = (ROOT/"web"/"app.html").read_text()
out = tpl.replace("/*__BOOKS__*/[]", data)
(ROOT/"web"/"index.html").write_text(out)
total = sum(len(c["text"]) for b in books for c in b["chapters"])
print(f"books={len(books)} chapters={sum(len(b['chapters']) for b in books)} chars={total} html={len(out)//1024}KB")
