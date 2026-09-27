/**
 * AI 生图「比例档 → 实际像素」映射（09-27-img-gen-size-ux）。
 *
 * 单一真源：用户按投放场景选**比例**，后端只认 OpenAI 兼容的 `size` 像素——白名单
 * `ImageService.ALLOWED_SIZES` = {1024x1024, 1536x1024, 1024x1536}，非白名单经 `normalizeSize`
 * 抛 `IllegalArgumentException` → 400「不支持的尺寸: xxx」。映射表**只存在于本文件**，
 * 不在前后端各写一份（写两份必然漂移）。
 *
 * 关键取舍（勿回改）：
 *  - 刻意只有 4 档、**不含 16:9**：它与 4:3 指向同一像素 1536x1024，两个选项给同一张图属重复。
 *  - **只做单向映射（档位 → 像素），不做反查（像素 → 档位）**：3:4 与 9:16 同为 1024x1536，
 *    反查必然歧义，会让用户在两档之间切换时选中态乱跳。比例选择态由调用方用独立 ref（`genRatio`）
 *    记忆，`genSize` 由其派生。
 *  - `gen_size` 落库仍是像素，`/{id}/regenerate` 用源图 `gen_size` 复现（`ImageService.regenerate`）、
 *    `utils/imageRefCache` 缓存条目存的也是像素——本模块不参与这三条既有链路，兼容零负担。
 *  - 非精确档必须在控件上写出实际像素（如 `9:16 · 实际 1024×1536`），不得让用户误以为拿到精确比例；
 *    `1:1` 档不显示「近似」类字样。本模块**不做后处理**（不裁切、不补边）。
 */
export const GEN_RATIOS = [
  { key: '1:1', size: '1024x1024', exact: true },
  { key: '4:3', size: '1536x1024', exact: false },   // 实际 3:2（最接近的官方横图）
  { key: '3:4', size: '1024x1536', exact: false },   // 实际 2:3（最接近的官方竖图）
  { key: '9:16', size: '1024x1536', exact: false }   // 实际 2:3（手机全屏，竖图近似）
]

/** 默认档（首次进入抽屉的选中态）。 */
export const GEN_RATIO_DEFAULT = '1:1'

/**
 * 比例档 → 后端白名单内的实际像素。未知/空档回落默认档。
 * 不抛错：调用方只可能来自 GEN_RATIOS（按钮点击）或历史值，容错优于让抽屉白屏。
 */
export function sizeOfRatio(key) {
  const hit = GEN_RATIOS.find((r) => r.key === key)
  return (hit || GEN_RATIOS[0]).size
}

/** 比例档 → CSS `aspect-ratio` 轮廓值（'9:16' → '9 / 16'），仅供选择器图形示意。 */
export function cssRatioOf(key) {
  return String(key || '').replace(':', ' / ')
}
