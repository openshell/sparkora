/**
 * 正文图片引用解析（09-27-image-insert-bugs）。
 *
 * 存在的理由：配图数量此前有三个互不一致的口径——预览工具栏拿「配图快照 images」当分母（含封面）、
 * 分子取「快照∩正文」；发布页数「版本-图片关联表」。三者都会与「用户实际看到的正文」脱节。
 *
 * 这里确立**唯一口径：解析正文 markdown 里的图片引用**。理由：
 * - 正文就是 wenyan 渲染与发布的真源（预览/发布同核），不会漏算也不会多算；
 * - 封面不参与：封面走 `sparkora_article_version.cover_image_id`，与正文插图是两件事；
 * - 关联表是**尽力而为**的辅助数据（手动编辑正文不会同步），不能当计数真值。
 *
 * 与 pendingImageStore 的关系：正文中 `![](sparkora-img:<id>)` 这类占位 token 单独归入
 * `tokenIds`，不计入 `urls`——它们尚未上传、不该被算作「已就绪插图」。
 */
import { TOKEN_PREFIX } from './pendingImageStore'

/**
 * markdown 图片引用 `![alt](target "title")`，只捕获目标串。
 * 容忍目标两侧空白、`target "title"` 形式，以及 alt 文本里的一层方括号（`![图 [1]](x)`）。
 *
 * 已知边界（node 回归实测）：`![](x)` / `![alt](x "t")` / alt 含一层方括号 / 同一 URL 重复出现
 * 均正确去重计数；纯链接 `[a](x)` 不计。**两处刻意接受的偏差**：① 转义写法 `\![a](x)` 仍计入；
 * ② 代码围栏内的 `![](x)` 字面量也计入（围栏里本不该是插图，但正文由编辑器产出、几乎不会出现，
 * 偏差方向为「多算」，而插图数只用于展示，不参与渲染/发布，故不特殊处理）。
 * 不处理转义括号（`![](a(1).png)` 截断到首个 `)`，与 CommonMark 裸 URL 限制一致）。
 */
const IMG_RE = /!\[[^[\]]*(?:\[[^[\]]*\])?[^[\]]*\]\(\s*([^)\s]+)(?:\s+["'][^"']*["'])?\s*\)/g

/**
 * 解析正文中的全部图片引用。
 * @param {string} md 正文 markdown
 * @returns {{urls: string[], tokenIds: string[]}}
 *   - `urls`：非 token 的图片目标（图床公网 URL 等），**按目标字符串去重**，保首次出现顺序；
 *   - `tokenIds`：`sparkora-img:<id>` 的 id，去重并保出现顺序（供「已失效/待上传」判断）。
 */
export function parseBodyImageRefs(md) {
  const urls = []
  const tokenIds = []
  const seenUrl = new Set()
  const seenToken = new Set()
  const re = new RegExp(IMG_RE.source, 'g')
  let m
  const text = String(md || '')
  while ((m = re.exec(text)) !== null) {
    const target = m[1]
    if (!target) continue
    if (target.startsWith(TOKEN_PREFIX)) {
      const id = target.slice(TOKEN_PREFIX.length)
      if (id && !seenToken.has(id)) { seenToken.add(id); tokenIds.push(id) }
    } else if (!seenUrl.has(target)) {
      seenUrl.add(target)
      urls.push(target)
    }
  }
  return { urls, tokenIds }
}

/**
 * 正文「已就绪」插图数（不含封面、不含未上传的粘贴图占位）。
 * 预览工具栏与发布页摘要共用此函数，保证两处口径一致。
 */
export function countBodyImages(md) {
  return parseBodyImageRefs(md).urls.length
}

export default { parseBodyImageRefs, countBodyImages }
