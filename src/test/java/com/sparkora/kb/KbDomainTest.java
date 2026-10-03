package com.sparkora.kb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KbDomain} 受控词表单测（10-03 E3）。
 */
class KbDomainTest {

    @Test
    void 词表保序含七个受控值() {
        assertEquals(7, KbDomain.all().size());
        assertEquals("通用", KbDomain.all().get(0));
        assertTrue(KbDomain.all().contains("充电"));
        assertTrue(KbDomain.all().contains("驾驶"));
    }

    @Test
    void normalize_空白归通用() {
        assertEquals("通用", KbDomain.normalize(null));
        assertEquals("通用", KbDomain.normalize(""));
        assertEquals("通用", KbDomain.normalize("   "));
    }

    @Test
    void normalize_trim后精确匹配() {
        assertEquals("充电", KbDomain.normalize("  充电 "));
        assertEquals("技术科普", KbDomain.normalize("技术科普"));
    }

    @Test
    void normalize_非词表值拒绝并附允许列表() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> KbDomain.normalize("财经"));
        assertTrue(ex.getMessage().contains("财经"), ex.getMessage());
        assertTrue(ex.getMessage().contains("通用"), ex.getMessage());
    }

    @Test
    void isValid_仅受控值通过() {
        assertTrue(KbDomain.isValid("安全"));
        assertTrue(KbDomain.isValid(" 政策 "));
        assertFalse(KbDomain.isValid("其他"));
        assertFalse(KbDomain.isValid(null));
        assertFalse(KbDomain.isValid("  "));
    }
}
