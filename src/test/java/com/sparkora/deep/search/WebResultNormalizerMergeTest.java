package com.sparkora.deep.search;

import com.sparkora.deep.search.WebResultNormalizer.WebHit;
import com.sparkora.deep.tool.SearchTool;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebResultNormalizer.merge 跨源合并单测(10-04-web-fanout-merge B-R3/AC-B4/AC-B5)。
 * 纯静态、无网络。
 */
class WebResultNormalizerMergeTest {

    private static WebHit hit(String provider, String title, String url) {
        return new WebHit(null, title, url, "snippet", provider);
    }

    private static Map<WebProvider, List<WebHit>> group(Object... kv) {
        Map<WebProvider, List<WebHit>> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            @SuppressWarnings("unchecked")
            List<WebHit> hits = (List<WebHit>) kv[i + 1];
            m.put((WebProvider) kv[i], hits);
        }
        return m;
    }

    /** 跨源去重:同一 URL 两 provider 只保留 1 条,首次出现的 provider 胜出(order 靠前优先)。 */
    @Test
    void 跨源去重_同URL只保留一条_首次出现provider胜出() {
        Map<WebProvider, List<WebHit>> per = group(
                WebProvider.TAVILY, List.of(hit("TAVILY", "t", "https://a.com/x")),
                WebProvider.SEARXNG, List.of(hit("SEARXNG", "s", "https://a.com/x")),
                WebProvider.SERPER, List.of(hit("SERPER", "p", "https://a.com/x")));
        List<WebHit> merged = WebResultNormalizer.merge(
                List.of(WebProvider.TAVILY, WebProvider.SEARXNG, WebProvider.SERPER), per, 10);
        assertEquals(1, merged.size(), "同一 URL 合并为一条");
        assertEquals("TAVILY", merged.get(0).provider(), "order 靠前的 provider 胜出");
        assertEquals(3, merged.get(0).witnessCount(), "三 provider 命中同一 URL → witnessCount=3");
    }

    /** witnessCount 正确累加:同一 URL 多 provider 命中累加;不同 URL 各计 1。 */
    @Test
    void witnessCount_同URL多provider累加() {
        Map<WebProvider, List<WebHit>> per = group(
                WebProvider.TAVILY, List.of(hit("TAVILY", "t1", "https://a.com/1"), hit("TAVILY", "t2", "https://a.com/2")),
                WebProvider.SEARXNG, List.of(hit("SEARXNG", "s2", "https://a.com/2")));
        List<WebHit> merged = WebResultNormalizer.merge(
                List.of(WebProvider.TAVILY, WebProvider.SEARXNG), per, 10);
        assertEquals(2, merged.size());
        WebHit h1 = merged.stream().filter(h -> h.url().endsWith("/1")).findFirst().orElseThrow();
        WebHit h2 = merged.stream().filter(h -> h.url().endsWith("/2")).findFirst().orElseThrow();
        assertEquals(1, h1.witnessCount());
        assertEquals(2, h2.witnessCount(), "两 provider 命中同一 URL → witnessCount=2");
    }

    /** 同 provider 多 endpoint 命中同一 URL 只累 witnessEndpoints,不进 witnessCount。 */
    @Test
    void 同provider多endpoint_只累witnessEndpoints() {
        Map<WebProvider, List<WebHit>> per = group(
                WebProvider.TAVILY, List.of(hit("TAVILY", "t", "https://a.com/x"), hit("TAVILY", "t2", "https://a.com/x")));
        List<WebHit> merged = WebResultNormalizer.merge(List.of(WebProvider.TAVILY), per, 10);
        assertEquals(1, merged.size());
        assertEquals(1, merged.get(0).witnessCount(), "同 provider 不抬升 witnessCount");
        assertEquals(2, merged.get(0).witnessEndpoints(), "多 endpoint 累 witnessEndpoints");
    }

    /** order 位次排序稳定:合并后先按 provider 在 order 中的位次,provider 内保持原 rank。 */
    @Test
    void order位次排序稳定() {
        Map<WebProvider, List<WebHit>> per = group(
                WebProvider.SEARXNG, List.of(hit("SEARXNG", "s1", "https://s.com/1"), hit("SEARXNG", "s2", "https://s.com/2")),
                WebProvider.TAVILY, List.of(hit("TAVILY", "t1", "https://t.com/1")));
        List<WebHit> merged = WebResultNormalizer.merge(
                List.of(WebProvider.TAVILY, WebProvider.SEARXNG), per, 10);
        assertEquals("https://t.com/1", merged.get(0).url(), "order 靠前 provider 的结果优先");
        assertEquals("https://s.com/1", merged.get(1).url(), "provider 内保持原 rank");
        assertEquals("https://s.com/2", merged.get(2).url());
    }

    /** 截断不放大:2 provider × 10 结果、maxResults=5 → 输出 5 条。 */
    @Test
    void 截断_2provider各10条_maxResults5输出5条() {
        List<WebHit> tenA = new java.util.ArrayList<>();
        List<WebHit> tenB = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) tenA.add(hit("TAVILY", "t" + i, "https://t.com/" + i));
        for (int i = 0; i < 10; i++) tenB.add(hit("SEARXNG", "s" + i, "https://s.com/" + i));
        Map<WebProvider, List<WebHit>> per = group(
                WebProvider.TAVILY, tenA, WebProvider.SEARXNG, tenB);
        List<WebHit> merged = WebResultNormalizer.merge(
                List.of(WebProvider.TAVILY, WebProvider.SEARXNG), per, 5);
        assertEquals(5, merged.size(), "输出不得超过 maxResults(未放大)");
    }

    /** sourceId 全局唯一且重排为 W1..Wn。 */
    @Test
    void sourceId_合并后全局唯一并重排() {
        Map<WebProvider, List<WebHit>> per = group(
                WebProvider.TAVILY, List.of(hit("TAVILY", "t", "https://t.com/1")),
                WebProvider.SEARXNG, List.of(hit("SEARXNG", "s", "https://s.com/1")));
        List<WebHit> merged = WebResultNormalizer.merge(
                List.of(WebProvider.TAVILY, WebProvider.SEARXNG), per, 10);
        assertEquals(2, merged.size());
        assertEquals("W1", merged.get(0).sourceId());
        assertEquals("W2", merged.get(1).sourceId());
        assertNotEquals(merged.get(0).sourceId(), merged.get(1).sourceId());
    }

    /**
     * 反例 1:同一 URL 被两源命中 → 合并后 sourceId 唯一,且 provider 与 URL 一致
     * (validateFacts 严格比对依赖此一致性)。
     */
    @Test
    void 反例_同URL双源命中_sourceId唯一且provider一致() {
        Map<WebProvider, List<WebHit>> per = group(
                WebProvider.TAVILY, List.of(hit("TAVILY", "t", "https://x.com/a")),
                WebProvider.SEARXNG, List.of(hit("SEARXNG", "s", "https://x.com/a")));
        List<WebHit> merged = WebResultNormalizer.merge(
                List.of(WebProvider.TAVILY, WebProvider.SEARXNG), per, 10);
        assertEquals(1, merged.size());
        assertEquals("W1", merged.get(0).sourceId());
        assertEquals("TAVILY", merged.get(0).provider());
        assertEquals("https://x.com/a", merged.get(0).url());
    }

    /**
     * 反例 2:同一 title 不同 URL → 不合并(按 URL 去重,不是按 title),
     * 各带独立 sourceId 与各自 provider;validateFacts 按各自 URL 比对通过。
     */
    @Test
    void 反例_同title不同URL_不合并各带独立sourceId() {
        Map<WebProvider, List<WebHit>> per = group(
                WebProvider.TAVILY, List.of(hit("TAVILY", "同一标题", "https://t.com/1")),
                WebProvider.SEARXNG, List.of(hit("SEARXNG", "同一标题", "https://s.com/1")));
        List<WebHit> merged = WebResultNormalizer.merge(
                List.of(WebProvider.TAVILY, WebProvider.SEARXNG), per, 10);
        assertEquals(2, merged.size(), "同 title 不同 URL 不合并");
        assertEquals("W1", merged.get(0).sourceId());
        assertEquals("TAVILY", merged.get(0).provider());
        assertEquals("https://t.com/1", merged.get(0).url());
        assertEquals("W2", merged.get(1).sourceId());
        assertEquals("SEARXNG", merged.get(1).provider());
        assertEquals("https://s.com/1", merged.get(1).url());
    }

    /** 非法 URL 在合并时剔除。 */
    @Test
    void 非法URL_合并时剔除() {
        Map<WebProvider, List<WebHit>> per = group(
                WebProvider.TAVILY, List.of(hit("TAVILY", "bad", "not-a-url"), hit("TAVILY", "ok", "https://t.com/1")));
        List<WebHit> merged = WebResultNormalizer.merge(List.of(WebProvider.TAVILY), per, 10);
        assertEquals(1, merged.size());
        assertEquals("W1", merged.get(0).sourceId());
    }

    /** 空输入不抛。 */
    @Test
    void 空输入_返回空不抛() {
        assertTrue(WebResultNormalizer.merge(List.of(WebProvider.TAVILY), null, 5).isEmpty());
        assertTrue(WebResultNormalizer.merge(List.of(WebProvider.TAVILY), Map.of(), 5).isEmpty());
    }

    /** merge 与 normalize 的 sourceId 语义衔接:toSearchHit 透传合并后的 sourceId/provider。 */
    @Test
    void 合并后toSearchHit透传sourceId与provider() {
        Map<WebProvider, List<WebHit>> per = group(
                WebProvider.TAVILY, List.of(hit("TAVILY", "t", "https://t.com/1")));
        List<WebHit> merged = WebResultNormalizer.merge(List.of(WebProvider.TAVILY), per, 10);
        SearchTool.SearchHit sh = merged.get(0).toSearchHit();
        assertEquals("W1", sh.sourceId());
        assertEquals("TAVILY", sh.provider());
        assertEquals("WEB", sh.type());
    }

    // ===== 10-04 C-R4:跨轮去重(seenUrls)=====

    /** 跨轮去重:Round 1 已见 URL 在 Round 2 被直接丢弃,并计入 dedupedSink。 */
    @Test
    void 跨轮去重_已见URL丢弃并计数() {
        Set<String> seen = new java.util.LinkedHashSet<>();
        Map<WebProvider, List<WebHit>> round1 = group(
                WebProvider.TAVILY, List.of(hit("TAVILY", "t1", "https://t.com/1")));
        List<WebHit> m1 = WebResultNormalizer.merge(List.of(WebProvider.TAVILY), round1, 10, seen);
        assertEquals(1, m1.size());
        assertEquals(1, seen.size(), "Round 1 产出的 URL 标为已见");

        java.util.concurrent.atomic.AtomicInteger deduped = new java.util.concurrent.atomic.AtomicInteger();
        Map<WebProvider, List<WebHit>> round2 = group(
                WebProvider.TAVILY, List.of(hit("TAVILY", "t1", "https://t.com/1"),
                        hit("TAVILY", "t2", "https://t.com/2")));
        List<WebHit> m2 = WebResultNormalizer.merge(List.of(WebProvider.TAVILY), round2, 10, seen, deduped::addAndGet);
        assertEquals(1, m2.size(), "已见 URL 被丢,仅新 URL 保留");
        assertEquals("https://t.com/2", m2.get(0).url());
        assertEquals(1, deduped.get(), "dedupedCount 计 1");
    }

    /** seenUrls=null 等价旧行为:不去重。 */
    @Test
    void seenUrls为null_旧行为等价() {
        Map<WebProvider, List<WebHit>> per = group(
                WebProvider.TAVILY, List.of(hit("TAVILY", "t1", "https://t.com/1")));
        List<WebHit> m1 = WebResultNormalizer.merge(List.of(WebProvider.TAVILY), per, 10, null);
        List<WebHit> m2 = WebResultNormalizer.merge(List.of(WebProvider.TAVILY), per, 10);
        assertEquals(1, m1.size());
        assertEquals("W1", m2.get(0).sourceId());
        assertEquals(m2.get(0).url(), m1.get(0).url());
    }
}
