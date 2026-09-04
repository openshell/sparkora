package com.sparkora.car.service;

import com.sparkora.domain.entity.CarParamCleanEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * S6b 切块质量单测:清洗值展示(cleanDisplay)规则。
 * 覆盖:清洗值优先/缺失回退 raw/双方缺失跳过、NUMBER+单位拼接、LIST+单位、值已含单位不重复拼、ENUM/STRING 不拼单位。
 */
class CarDocCleanDisplayTest {

    private static CarParamCleanEntity e(String valueType, String value, String unit, String raw) {
        CarParamCleanEntity c = new CarParamCleanEntity();
        c.setValueType(valueType);
        c.setParamValue(value);
        c.setUnit(unit);
        c.setRawValue(raw);
        return c;
    }

    @Test
    void 清洗值优先于原始值() {
        CarParamCleanEntity c = e("NUMBER", "2820", "mm", "旧原始值");
        assertEquals("2820mm", CarDocService.cleanDisplay(c));
    }

    @Test
    void 清洗值缺失回退原始值() {
        CarParamCleanEntity c = e("NUMBER", null, "mm", "1650/1670");
        assertEquals("1650/1670mm", CarDocService.cleanDisplay(c));
    }

    @Test
    void 双缺失返回null跳过该行() {
        assertNull(CarDocService.cleanDisplay(e("NUMBER", null, "mm", "  ")));
        assertNull(CarDocService.cleanDisplay(e("STRING", null, null, null)));
    }

    @Test
    void LIST拼接单位() {
        CarParamCleanEntity c = e("LIST", "4810×1920×1675", "mm", null);
        assertEquals("4810×1920×1675mm", CarDocService.cleanDisplay(c));
    }

    @Test
    void 值已含单位不重复拼接() {
        CarParamCleanEntity c = e("NUMBER", "205km", "km", null);
        assertEquals("205km", CarDocService.cleanDisplay(c));
    }

    @Test
    void ENUM与STRING不拼单位() {
        assertEquals("有", CarDocService.cleanDisplay(e("ENUM", "有", "mm", null)));
        assertEquals("EHS电混系统", CarDocService.cleanDisplay(e("STRING", "EHS电混系统", null, null)));
    }

    @Test
    void 无单位数值原样() {
        assertEquals("2820", CarDocService.cleanDisplay(e("NUMBER", "2820", null, null)));
    }
}