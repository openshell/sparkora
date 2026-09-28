<template>
  <div class="img-card"
       :class="{ selected, highlight }"
       @click="emit('card-click', img)">
    <!-- 缩略图 + 连续大图预览(当前页全部原图) -->
    <el-image :src="imgUrl(img)" fit="cover" class="img-thumb" loading="lazy"
              :preview-src-list="selectMode ? [] : pageOriginUrls" :initial-index="pageOriginUrls.indexOf(originUrl(img))"
              preview-teleported hide-on-click-modal @click.stop />
    <!-- 来源小标:色点 + 文字 -->
    <span class="src-tag" :class="'src-' + img.source">
      <span class="src-tag-dot"></span>{{ sourceLabel(img.source) }}
    </span>
    <!-- 选中态 checkbox(选择模式) -->
    <span v-if="selectMode" class="check-box" :class="{ checked: selected }">
      <el-icon v-if="selected"><Check /></el-icon>
    </span>
    <!-- 桌面 hover 层:元数据 + 操作 -->
    <div class="hover-panel">
      <div class="hp-meta">
        <div class="hp-name" :title="img.fileName">{{ img.fileName }}</div>
        <div class="hp-sub">{{ hpSubText(img, projects) }}</div>
        <!-- 语义搜索命中：显示相关度分数与嵌入原文（可解释性） -->
        <div v-if="img.score != null" class="hp-score" :title="img.sourceText">
          相关度 {{ (img.score * 100).toFixed(0) }}%<span v-if="img.sourceText" class="hp-score-why"> · {{ img.sourceText }}</span>
        </div>
        <div v-else class="hp-sub">{{ img.createdBy }} · {{ shortTime(img.createdAt) }}</div>
        <!-- 来源追溯行（09-15 img-classify）：新闻图显示「来源：<标题> · <日期>」，点击跳原文 -->
        <div v-if="sourceInfo && sourceInfo.news" class="hp-src">
          <span class="hp-src-text" :title="sourceInfo.news.title"
                @click.stop="emit('open-news', sourceInfo)">
            来源：{{ sourceInfo.news.title }} · {{ shortDay(sourceInfo.news.publishDate) }}
          </span>
        </div>
        <div v-if="img.genModel" class="hp-gen">{{ img.genModel }}{{ img.genSize ? ' · ' + img.genSize : '' }}</div>
        <!-- 标签行:点击标签直接筛选(09-13 image-tags) -->
        <div v-if="img.tags && img.tags.length" class="hp-tags">
          <span v-for="t in img.tags" :key="t" class="hp-tag" :title="`按「${t}」筛选`"
                @click.stop="emit('filter-tag', t)">{{ t }}</span>
        </div>
      </div>
      <div class="hp-actions">
        <el-button v-if="canEdit" size="small" text
                   @click.stop="emit('edit-tags', img)">编辑标签</el-button>
        <el-tooltip v-if="isAiImage(img) && canEdit"
                    :disabled="canRegenerate" content="参考图未入库且会话缓存已失效，无法重生成" placement="top">
          <span class="regen-wrap">
            <el-button size="small" text type="primary" :disabled="!canRegenerate"
                       :loading="regenerating" @click.stop="emit('regen', img)">重生成</el-button>
          </span>
        </el-tooltip>
        <el-button v-if="canEdit" size="small" text type="danger" :loading="deleting"
                   @click.stop="emit('delete', img)">删除</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { Check } from '@element-plus/icons-vue'
import { imgUrl, originUrl, sourceLabel, shortTime, isAiImage, hpSubText } from '../../utils/imageDisplay'

/**
 * 图库卡片（缩略图 + hover 桌面元数据/操作）。
 * 从 ImageLibrary.vue 抽出（09-27-split-monoliths，纯结构重构，零行为变化）。
 * 纯声明式：所有动作经 emits 交父执行，组件不直接改宿主状态。
 * PC-only（09-28-pc-ui-refactor 批1）：原 `.mobile-bar` 常显行与移动端常显标签/来源/分数行已退役，
 * 元数据与操作统一走 hover 层。
 */
const props = defineProps({
  img: { type: Object, required: true },
  selectMode: { type: Boolean, default: false },
  selected: { type: Boolean, default: false },
  highlight: { type: Boolean, default: false },
  sourceInfo: { type: Object, default: null },
  projects: { type: Array, default: () => [] },
  canEdit: { type: Boolean, default: false },
  canRegenerate: { type: Boolean, default: false },
  deleting: { type: Boolean, default: false },
  regenerating: { type: Boolean, default: false },
  pageOriginUrls: { type: Array, default: () => [] }
})
const emit = defineEmits(['card-click', 'filter-tag', 'edit-tags', 'regen', 'delete', 'open-news'])

const shortDay = (t) => (t ? String(t).replace('T', ' ').slice(0, 10) : '')
</script>

<style scoped>
/* 卡片:缩略图 + hover 元数据层(PC-only 唯一交互面) */
.img-card { position: relative; border-radius: var(--radius-md); overflow: hidden; background: var(--card); cursor: pointer; transition: box-shadow .2s, outline-color .15s ease; }
.img-card:hover { box-shadow: var(--shadow-2); }
.img-card.selected { outline: 2px solid var(--brand); outline-offset: -2px; }
.img-card.highlight { animation: hl-pulse 2s ease-out; }
@keyframes hl-pulse {
  0% { box-shadow: 0 0 0 4px var(--brand); }
  100% { box-shadow: 0 0 0 12px transparent; }
}
@media (prefers-reduced-motion: reduce) {
  .img-card { transition: none; }
  .img-card.highlight { animation: none; box-shadow: 0 0 0 4px var(--brand); }
}
.img-thumb { width: 100%; aspect-ratio: 4 / 3; display: block; background: var(--n-100); }

/* 来源小标:色点 + 文字 */
.src-tag { position: absolute; top: var(--sp-4); left: var(--sp-4); display: inline-flex; align-items: center; gap: var(--sp-2); padding: 2px var(--sp-3) 2px var(--sp-2); border-radius: 999px; background: rgba(0,0,0,.55); color: #fff; font-size: var(--fs-11); line-height: 1; backdrop-filter: blur(4px); pointer-events: none; }
.src-tag-dot { width: 6px; height: 6px; border-radius: 50%; flex: none; }
.src-tag.src-upload .src-tag-dot { background: #67c23a; }
.src-tag.src-ai-text2img .src-tag-dot { background: #409eff; }
.src-tag.src-ai-img2img .src-tag-dot { background: #9b59b6; }
.src-tag.src-byd .src-tag-dot { background: #e6a23c; }
.src-tag.src-byd-news .src-tag-dot { background: #f56c6c; }

/* 选择模式 checkbox */
.check-box { position: absolute; top: var(--sp-4); right: var(--sp-4); width: 20px; height: 20px; border-radius: var(--radius-sm); background: rgba(255,255,255,.9); border: 1.5px solid var(--line-strong); display: flex; align-items: center; justify-content: center; color: transparent; }
.check-box.checked { background: var(--brand); border-color: var(--brand); color: #fff; }

/* hover 层:元数据 + 操作 */
.hover-panel { position: absolute; left: 0; right: 0; bottom: 0; padding: var(--sp-7) var(--sp-5) var(--sp-4); background: linear-gradient(to top, rgba(0,0,0,.78), rgba(0,0,0,.45) 70%, transparent); color: #fff; opacity: 0; transition: opacity .2s; pointer-events: none; }
.img-card:hover .hover-panel, .img-card:focus-within .hover-panel { opacity: 1; pointer-events: auto; }
.hp-meta { margin-bottom: var(--sp-3); }
.hp-name { font-size: var(--fs-12); font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hp-sub { font-size: var(--fs-11); opacity: .85; margin-top: var(--sp-1); }
.hp-gen { font-size: var(--fs-11); opacity: .7; margin-top: var(--sp-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* hover 层语义分数行（09-15 img-semantic-search）：分数 + 嵌入原文截断，title 显示全文 */
.hp-score { font-size: var(--fs-11); opacity: .92; margin-top: var(--sp-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hp-score-why { opacity: .8; }
.hp-actions { display: flex; gap: var(--sp-3); justify-content: flex-end; flex-wrap: wrap; }
.hp-actions .el-button { color: #fff; }
/* 置灰态（本地参考图缓存失效）：scoped 规则优先级高于 element 默认 disabled 色，需显式回退 */
.hp-actions .el-button.is-disabled { color: rgba(255,255,255,.4); }
.regen-wrap { display: inline-flex; }
/* hover 层标签行:点击筛选 */
.hp-tags { display: flex; flex-wrap: wrap; gap: var(--sp-2); margin-top: var(--sp-2); }
.hp-tag { font-size: var(--fs-11); line-height: 1; padding: 3px var(--sp-3); border-radius: 999px; background: rgba(255,255,255,.22); color: #fff; cursor: pointer; pointer-events: auto; }
.hp-tag:hover { background: rgba(255,255,255,.38); }
/* hover 层来源追溯行（09-15 img-classify）：点击跳新闻原文 */
.hp-src { margin-top: var(--sp-2); }
.hp-src-text { display: inline-block; max-width: 100%; font-size: var(--fs-11); opacity: .92; cursor: pointer; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; pointer-events: auto; text-decoration: underline dotted; }
.hp-src-text:hover { opacity: 1; }
</style>
