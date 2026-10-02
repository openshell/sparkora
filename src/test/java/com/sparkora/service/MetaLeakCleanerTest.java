package com.sparkora.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 10-02-fix-meta-leak-in-article-body R3:{@link MetaLeakCleaner} 单测。
 *
 * <p>三类断言:①真实泄漏样本(version 44 第 17/25 段)零残留、段落结构不塌;
 * ②正常正文逐字零误删(数值句/官方归因句/标题/列表);③幂等与边界(null/空/无泄漏/全段泄漏)。
 */
class MetaLeakCleanerTest {

    // ==================== AC3:真实泄漏样本(version 44,2026-10-02 生成) ====================

    /** 第 17 段:整段唯一一句即泄漏句(手册未提供…)→ 整段消失。 */
    private static final String LEAK_P17 =
            "合资品牌同期在华销量的具体数据，手册未提供，只能提示一个方向：份额承压仍在继续。";

    /** 第 25 段:前两句泄漏(手册未披露/无法计算 + 不应作为结论),末句正常 → 只保留末句。 */
    private static final String LEAK_P25 =
            "需要说明的是，手册未披露其年度销量目标，完成率无法计算；也没有行业排名或份额数据，"
            + "“全球最高之一”这类定性不应作为结论输出。"
            + "可确认的只有一件事：随着第四季度开启，多品牌协同与出海提速的双轮驱动，会继续改写全球新能源汽车市场的竞争格局。";

    @Test
    void 真实泄漏样本_第17段零残留() {
        MetaLeakCleaner.CleanResult r = MetaLeakCleaner.clean(LEAK_P17);
        assertEquals("", r.content(), "唯一一句即泄漏句,整段应被删除");
        assertEquals(1, r.removed().size());
        assertTrue(r.removed().get(0).contains("手册未提供"), "removed 应记录被删原文");
    }

    @Test
    void 真实泄漏样本_第25段零残留且保留正常句() {
        MetaLeakCleaner.CleanResult r = MetaLeakCleaner.clean(LEAK_P25);
        assertFalse(r.content().contains("手册未披露"), "泄漏词必须消失");
        assertFalse(r.content().contains("无法计算"), "泄漏词必须消失");
        assertFalse(r.content().contains("不应作为结论"), "泄漏词必须消失");
        assertFalse(r.content().contains("也没有行业排名"), "同段元话语必须消失");
        assertEquals("可确认的只有一件事：随着第四季度开启，多品牌协同与出海提速的双轮驱动，会继续改写全球新能源汽车市场的竞争格局。",
                r.content(), "同段正常句必须逐字保留");
    }

    /** 真实整篇:正常段落逐字保留、两处泄漏段落消失、标题不误删。 */
    @Test
    void 真实整篇_只删泄漏段其余逐字保留() {
        String md = """
                # 一个月46万辆、海外同比153.9%：比亚迪9月成绩单，合资品牌该怎么读？

                ## 一、46.3万辆的含金量：单月新高是怎么堆出来的

                比亚迪2026年9月销量达**46.3万辆**。据媒体报道，同期集团单月累计销售新车463,561辆，其中新能源汽车销量为46.36万辆——三个数字口径不同，但指向同一件事。

                ## 二、拆品牌：王朝海洋近40万辆

                合资品牌同期在华销量的具体数据，手册未提供，只能提示一个方向：份额承压仍在继续。

                ## 三、出口近18万辆、第二增长曲线已经成形

                出海是这份成绩单里最硬的一块。累计规模同样构成压力。需要说明的是，手册未披露其年度销量目标，完成率无法计算；也没有行业排名或份额数据，“全球最高之一”这类定性不应作为结论输出。可确认的只有一件事：会继续改写全球新能源汽车市场的竞争格局。
                """;

        MetaLeakCleaner.CleanResult r = MetaLeakCleaner.clean(md);

        // 泄漏消失
        assertFalse(r.content().contains("手册未提供"));
        assertFalse(r.content().contains("手册未披露"));
        assertFalse(r.content().contains("无法计算"));
        // 标题与正常段落逐字保留
        assertTrue(r.content().contains("# 一个月46万辆、海外同比153.9%：比亚迪9月成绩单，合资品牌该怎么读？"));
        assertTrue(r.content().contains("## 二、拆品牌：王朝海洋近40万辆"));
        assertTrue(r.content().contains("比亚迪2026年9月销量达**46.3万辆**。"));
        assertTrue(r.content().contains("据媒体报道，同期集团单月累计销售新车463,561辆"));
        assertTrue(r.content().contains("出海是这份成绩单里最硬的一块。累计规模同样构成压力。"));
        assertTrue(r.content().contains("会继续改写全球新能源汽车市场的竞争格局。"));
        // 泄漏所在段落整体消失(第 17 段整段只有一句元话语)
        assertEquals(3, r.removed().size(), "应恰好删三句(第 17 段 1 句 + 第 25 段 ；分句 2 句)");
        assertTrue(r.content().contains("## 二") && !r.content().contains("份额承压仍在继续"),
                "第 17 段应整体消失,但标题保留");
    }

    // ==================== AC4:正常正文零误删(逐句) ====================

    /** 必须原样保留的正常句(AC4 反例清单):逐句 clean 后必须与入参逐字相等。 */
    private static final List<String> KEEP_VERBATIM = List.of(
            "比亚迪2026年9月销量达**46.3万辆**。",
            "据媒体报道，同期集团单月累计销售新车463,561辆，乘用车销售456,713辆。",
            "详细参数以官方发布为准。",
            "参数请以官方发布为准，具体权益以门店公示为准。",
            "王朝丨海洋系列9月销量为**394,945辆**，对比集团463,561辆，占比约**85%**，基本盘地位没有松动。",
            "9月纯电动车销量为27.31万辆、同比增长33.21%，插电式混合动力汽车销量为18.36万辆。",
            "海外市场同比大增**153.9%**，海外渠道拓展与产品矩阵导入正在形成规模效应。",
            "价格与配置可到当地门店咨询，具体以官方发布为准。",
            "该车型暂无现金优惠，置换政策以当地公告为准。",
            "这个比例值得琢磨：纯电跑得更快，插混守住近四成。"
    );

    @Test
    void 正常句_逐句零误删() {
        for (String s : KEEP_VERBATIM) {
            MetaLeakCleaner.CleanResult r = MetaLeakCleaner.clean(s);
            assertEquals(s, r.content(), "正常句不得被改写或删除: " + s);
            assertTrue(r.removed().isEmpty(), "正常句不得产生删除记录: " + s);
        }
    }

    /** Markdown 标题 / 列表 / 分节结构零误删(AC4)。 */
    @Test
    void 标题与列表结构_零误删() {
        String md = """
                # 标题

                ## 小标题

                - 第一条：价格 23.99 万
                - 第二条：续航 700km
                - 第三条：详细参数以官方发布为准

                > 引用一句销售表现。

                | 参数 | 数值 |
                | --- | --- |
                | 价格 | 23.99 万 |
                """;

        MetaLeakCleaner.CleanResult r = MetaLeakCleaner.clean(md);

        assertEquals(md, r.content(), "标题/列表/表格必须逐字保留");
        assertTrue(r.removed().isEmpty());
    }

    /** 泄漏句夹在列表中:只删该行,相邻列表项不受影响(块起始换行作句界)。 */
    @Test
    void 列表中泄漏行_只删该行不伤相邻项() {
        String md = """
                - 9月销量463,561辆
                - 手册未提供合资品牌同期销量
                - 海外销量179,877辆
                """;

        MetaLeakCleaner.CleanResult r = MetaLeakCleaner.clean(md);

        assertTrue(r.content().contains("- 9月销量463,561辆"));
        assertTrue(r.content().contains("- 海外销量179,877辆"));
        assertFalse(r.content().contains("手册未提供"));
        assertFalse(r.content().contains("\n\n"), "删一行不得留下空行/空段");
        assertEquals(1, r.removed().size());
    }

    // ==================== AC5:幂等 ====================

    @Test
    void 幂等_对自身输出再跑一次结果不变() {
        List<String> inputs = List.of(
                md(LEAK_P17, LEAK_P25),
                md("# 标题", "正常正文一句。", "- 列表项：续航 700km"),
                LEAK_P25,
                "## 小标题\n\n手册未提供年度目标。\n\n可确认的事实：销量创新高。"
        );
        for (String in : inputs) {
            String once = MetaLeakCleaner.clean(in).content();
            assertEquals(once, MetaLeakCleaner.clean(once).content(), "清洗必须幂等: " + in);
        }
    }

    // ==================== 边界 ====================

    @Test
    void 边界_null与空白原样返回() {
        assertEquals(null, MetaLeakCleaner.clean(null).content());
        assertTrue(MetaLeakCleaner.clean(null).removed().isEmpty());
        assertEquals("", MetaLeakCleaner.clean("").content());
        assertEquals("   ", MetaLeakCleaner.clean("   ").content());
    }

    @Test
    void 边界_无泄漏正文逐字不变() {
        String md = "# 标题\n\n第一段正常。\n\n第二段也正常。\n";
        MetaLeakCleaner.CleanResult r = MetaLeakCleaner.clean(md);
        assertEquals(md, r.content(), "无命中时正文必须逐字不变");
        assertTrue(r.removed().isEmpty());
    }

    /** 全段泄漏 → 段落消失,不留下空行;两侧段落仍在。 */
    @Test
    void 边界_全段泄漏段落消失不留空行() {
        String md = md("正常一句。", "该断言手册未收录，无法计算完成率。", "另一句正常。");

        MetaLeakCleaner.CleanResult r = MetaLeakCleaner.clean(md);

        assertFalse(r.content().contains("手册未收录"));
        assertFalse(r.content().contains("无法计算"));
        assertFalse(r.content().contains("\n\n\n"), "不得出现连续空行");
        assertTrue(r.content().contains("正常一句。"));
        assertTrue(r.content().contains("另一句正常。"));
    }

    /** 仅标题:不删标题行,零命中时原样返回(含末尾换行)。 */
    @Test
    void 边界_仅标题保留不删() {
        MetaLeakCleaner.CleanResult r = MetaLeakCleaner.clean("# 一个月46万辆：比亚迪9月成绩单\n");
        assertEquals("# 一个月46万辆：比亚迪9月成绩单\n", r.content());
        assertTrue(r.removed().isEmpty());
    }

    /** 命中时重组段落:连续空行压成 1 个(零命中 = 零改写,不做任何空白归一)。 */
    @Test
    void 边界_命中时连续空行压成一个() {
        String md = "第一段。\n\n\n\n第二段。\n\n\n手册未提供年度目标。";
        assertEquals("第一段。\n\n第二段。", MetaLeakCleaner.clean(md).content());
        // 零命中:逐字不变(不归一空白)
        assertEquals("第一段。\n\n\n\n第二段。", MetaLeakCleaner.clean("第一段。\n\n\n\n第二段。").content());
    }

    // ==================== 补充回归(Check 阶段发现并修复的过度删除) ====================

    /** 回归:表格是空行分隔的独立段,一行泄漏曾导致<b>整张表</b>(表头+分隔行+正常行)被整删。 */
    @Test
    void 表格中泄漏行_只删该行不伤表头与正常行() {
        String md = """
                | 指标 | 值 |
                | --- | --- |
                | 合资销量 | 手册未提供 |
                | 出口 | 179,877 |
                """;

        MetaLeakCleaner.CleanResult r = MetaLeakCleaner.clean(md);

        // 期望值用 strip():清洗器重组时会裁掉段尾空白(段内结构不变)
        assertEquals("""
                | 指标 | 值 |
                | --- | --- |
                | 出口 | 179,877 |""", r.content(), "只应删掉含元话语的那一行");
        assertEquals(1, r.removed().size());
    }

    /** 回归:有序列表项「1. / 2. / 3.」曾不被识别为块起始,泄漏会牵连相邻项。 */
    @Test
    void 有序列表中泄漏项_只删该项() {
        String md = """
                1. 总量：46.36万辆
                2. 合资：手册未提供
                3. 出口：179,877辆
                """;

        MetaLeakCleaner.CleanResult r = MetaLeakCleaner.clean(md);

        assertTrue(r.content().contains("1. 总量：46.36万辆"));
        assertTrue(r.content().contains("3. 出口：179,877辆"));
        assertFalse(r.content().contains("手册未提供"));
        assertEquals(1, r.removed().size());
    }

    /** 回归:软换行多行块命中时收窄到行级删除,块内其他正常行不得被连带删除。 */
    @Test
    void 软换行块命中_只删命中行保留相邻正常行() {
        String md = """
                第一段正常铺垫，交代背景。
                第二句开始讲合资，手册未提供合资品牌同期销量。
                第三句继续正常收尾，会继续改写竞争格局。
                """;

        MetaLeakCleaner.CleanResult r = MetaLeakCleaner.clean(md);

        assertEquals("""
                第一段正常铺垫，交代背景。
                第三句继续正常收尾，会继续改写竞争格局。""", r.content());
        assertEquals(1, r.removed().size());
    }

    /** cleanForPersist:整篇皆为元话语(清洗致空)时回退原文,不落空正文(10-02 design §5.4)。 */
    @Test
    void cleanForPersist_清洗致空回退原文() {
        String md = "手册未提供年度目标。\n\n完成率无法计算。";

        MetaLeakCleaner.CleanResult clean = MetaLeakCleaner.clean(md);
        assertTrue(clean.content().isBlank(), "clean 本身仍应删空(纯函数语义不变)");

        MetaLeakCleaner.CleanResult persist = MetaLeakCleaner.cleanForPersist(md);
        assertEquals(md, persist.content(), "落库路径不得回写空正文");
        assertEquals(2, persist.removed().size(), "仍保留删除记录供审计");
    }

    /** cleanForPersist:只剩标题(清洗删到无正文)同样回退原文。 */
    @Test
    void cleanForPersist_只剩标题回退原文() {
        String md = "# 标题\n\n手册未提供年度目标。";

        assertEquals("# 标题", MetaLeakCleaner.clean(md).content());
        assertEquals(md, MetaLeakCleaner.cleanForPersist(md).content(), "空壳正文不得落库");
    }

    /** cleanForPersist:原文本就无正文(仅标题)或零命中时原样返回,不做无谓回退。 */
    @Test
    void cleanForPersist_零命中与本无正文原样返回() {
        assertEquals("# 只有标题\n", MetaLeakCleaner.cleanForPersist("# 只有标题\n").content());
        String cleanMd = "# 标题\n\n正常一句。";
        assertEquals(cleanMd, MetaLeakCleaner.cleanForPersist(cleanMd).content());
        assertTrue(MetaLeakCleaner.cleanForPersist(null).content() == null);
    }

    /** 独立验证样本(Check 阶段补充,非实施者样本):多级缩进/图片/html/英文长行等零误删。 */
    @Test
    void 独立样本_常见正文结构零误删() {
        List<String> samples = List.of(
                "- 一级项：46.36万辆\n  - 二级项：700km\n  - 二级项：正常描述\n- 一级项二：179,877辆",
                "![配图](https://img.example.com/a.jpg)\n\n正文一句。",
                "<p>正文一句，带 <strong>加粗</strong>。</p>\n\n第二段。",
                "The all-new BYD Seal U DM-i delivers a 1.5L engine with 120km of pure-electric range.",
                "纯电、插混、增程，三条路线并行；王朝、海洋、腾势、方程豹、仰望，多品牌协同。",
                "海外同比增长153.9%（同比口径），出口179,877辆。",
                "在《新能源汽车产业发展规划（2021—2035年）》之后，行业进入淘汰赛。",
                "他说“这组数据没有出处”，我们仍保留这句引述，因为它是采访原话，可核对来源。",
                "门店暂无现车，可下定锁单，具体交付时间以销售顾问告知为准。",
                "该配置的具体参数待核实，请以官网公布的配置表为准。",
                "按 61,379 ÷ 463,561 计算，海外占比约 13.2%，仍处于爬坡期。"
        );
        for (String s : samples) {
            MetaLeakCleaner.CleanResult r = MetaLeakCleaner.clean(s);
            assertEquals(s, r.content(), "不得误删/改写: " + s);
            assertTrue(r.removed().isEmpty(), "不得产生删除记录: " + s);
        }
    }

    /** 中文引号/书名号内的句读不得被当作句界切错(引述与书名号内容保留)。 */
    @Test
    void 引号与书名号内句读_不切错不误删() {
        String quoted = "他说“这组数据没有出处”，我们还是保留了这句引述，因为它是采访原话，可核对来源。";
        String book = "详见《比亚迪二十年纪要。上篇》第 3 章，那里记录了完整的数据来源。";
        assertEquals(quoted, MetaLeakCleaner.clean(quoted).content());
        assertEquals(book, MetaLeakCleaner.clean(book).content());
    }

    private static String md(String... paragraphs) {
        return String.join("\n\n", paragraphs);
    }
}
