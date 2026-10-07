package com.sparkora.deep.search;

import com.sparkora.deep.search.WebResultNormalizer.WebHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSearchOutcome 契约增量单测(10-04-web-fanout-merge B-R4/AC-B6):
 * usedProviders 为新增字段,usedProvider 旧字段与语义不变,旧构造器仍可用。
 */
class WebSearchOutcomeTest {

    private static final WebHit H = new WebHit("W1", "t", "https://x.com/a", "s", "TAVILY");

    /** 旧 3 参构造器:usedProviders 由 usedProvider 派生(单元素)。 */
    @Test
    void 旧3参构造器_usedProviders派生自usedProvider() {
        WebSearchOutcome out = new WebSearchOutcome(List.of(H), WebProvider.TAVILY,
                List.of(new WebSearchOutcome.Attempt(WebProvider.TAVILY, 1, 10L, null, true)));
        assertEquals(WebProvider.TAVILY, out.usedProvider(), "旧字段语义不变");
        assertEquals(List.of(WebProvider.TAVILY), out.usedProviders(), "新字段由旧字段派生");
    }

    /** 旧 3 参构造器 usedProvider=null → usedProviders 空。 */
    @Test
    void 旧3参构造器_无命中_usedProviders为空() {
        WebSearchOutcome out = new WebSearchOutcome(List.of(), null, List.of());
        assertNull(out.usedProvider());
        assertTrue(out.usedProviders().isEmpty());
    }

    /** 新 4 参构造器:FANOUT 下 usedProvider=首个命中,usedProviders=全部。 */
    @Test
    void 新4参构造器_usedProviders完整() {
        WebSearchOutcome out = new WebSearchOutcome(List.of(H), WebProvider.TAVILY, List.of(),
                List.of(WebProvider.TAVILY, WebProvider.SEARXNG));
        assertEquals(WebProvider.TAVILY, out.usedProvider());
        assertEquals(List.of(WebProvider.TAVILY, WebProvider.SEARXNG), out.usedProviders());
    }

    /** Attempt 兼容构造器(5 参)witnessTotal=0;新 6 参记实际值。 */
    @Test
    void Attempt_兼容构造器与witnessTotal() {
        WebSearchOutcome.Attempt old = new WebSearchOutcome.Attempt(WebProvider.TAVILY, 2, 30L, null, true);
        assertEquals(0, old.witnessTotal());
        WebSearchOutcome.Attempt neu = new WebSearchOutcome.Attempt(WebProvider.TAVILY, 2, 30L, null, true, 1);
        assertEquals(1, neu.witnessTotal());
    }
}
