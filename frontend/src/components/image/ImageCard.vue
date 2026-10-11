<template>
  <div class="img-card"
       :class="{ selected, highlight }"
       @click="emit('card-click', img, index)">
    <!-- 缩略图:纯展示;全屏预览由宿主 viewer 承载(点卡片任意处打开,10-10-img-gallery-ui-polish) -->
    <el-image :src="imgUrl(img)" fit="cover" class="img-thumb" loading="lazy" />
    <!-- 来源小标:色点 + 文字 -->
    <span class="src-tag" :class="'src-' + img.source">
      <span class="src-tag-dot"></span>{{ sourceLabel(img.source) }}
    </span>
    <!-- 语义相关度角标(语义模式、非选择态):非交互,点击穿透到卡片根 → 预览;命中原文下沉到预览面板 -->
    <span v-if="img.score != null && !selectMode" class="score-badge">{{ (img.score * 100).toFixed(0) }}%</span>
    <!-- 选中态 checkbox(选择模式) -->
    <span v-if="selectMode" class="check-box" :class="{ checked: selected }">
      <el-icon v-if="selected"><Check /></el-icon>
    </span>
    <!-- 桌面 hover 层:文件名/副行/标签/操作。非交互行 pointer-events:none,
         点击穿透到卡片根 → 全屏预览;标签/操作为显式 auto + @click.stop 不被预览劫持 -->
    <div class="hover-panel">
      <div class="hp-meta">
        <div class="hp-name" :title="img.fileName">{{ img.fileName }}</div>
        <div class="hp-sub">{{ hpSubText(img, projects) }}</div>
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
import { imgUrl, sourceLabel, isAiImage, hpSubText } from '../../utils/imageDisplay'

/**
 * 图库卡片（缩略图 + hover 桌面元数据/操作）。
 * 从 ImageLibrary.vue 抽出（09-27-split-monoliths）；10-10-img-gallery-ui-polish 交互重设计：
 * - hover 层收窄为 4 组（文件名/副行/标签/操作），生成模型/尺寸、来源追溯、相关度原文下沉到预览面板；
 * - 预览触发从「点 el-image」改为「点卡片任意处」：el-image 纯展示，卡片根 @click 上抛
 *   (img, index)（index 由 v-for 直传，宿主按索引定位，废除 indexOf(url) 重名错位）；
 * - hover 层恒定 pointer-events:none，仅标签行/操作按钮行恢复 auto + @click.stop；
 * - 语义模式相关度改为卡片右上角标（非交互）。
 * 纯声明式：所有动作经 emits 交父执行，组件不直接改宿主状态。
 */
const props = defineProps({
  img: { type: Object, required: true },
  /** v-for 索引（宿主按索引打开预览，替代按 URL indexOf 定位） */
  index: { type: Number, default: 0 },
  selectMode: { type: Boolean, default: false },
  selected: { type: Boolean, default: false },
  highlight: { type: Boolean, default: false },
  projects: { type: Array, default: () => [] },
  canEdit: { type: Boolean, default: false },
  canRegenerate: { type: Boolean, default: false },
  deleting: { type: Boolean, default: false },
  regenerating: { type: Boolean, default: false }
})
const emit = defineEmits(['card-click', 'filter-tag', 'edit-tags', 'regen', 'delete'])
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
/* 语义相关度角标:半透明深底白字(仿 src-tag),恒定非交互,点击穿透到卡片根 */
.score-badge { position: absolute; top: var(--sp-4); right: var(--sp-4); padding: 2px var(--sp-3); border-radius: 999px; background: rgba(0,0,0,.55); color: #fff; font-size: var(--fs-11); line-height: 1; backdrop-filter: blur(4px); pointer-events: none; }

/* 选择模式 checkbox */
.check-box { position: absolute; top: var(--sp-4); right: var(--sp-4); width: 20px; height: 20px; border-radius: var(--radius-sm); background: rgba(255,255,255,.9); border: 1.5px solid var(--line-strong); display: flex; align-items: center; justify-content: center; color: transparent; }
.check-box.checked { background: var(--brand); border-color: var(--brand); color: #fff; }

/* hover 层:元数据 + 操作(恒定 pointer-events:none,非交互行点击穿透到卡片根 → 预览) */
.hover-panel { position: absolute; left: 0; right: 0; bottom: 0; padding: var(--sp-7) var(--sp-5) var(--sp-4); background: linear-gradient(to top, rgba(0,0,0,.78), rgba(0,0,0,.45) 70%, transparent); color: #fff; opacity: 0; transition: opacity .2s; pointer-events: none; }
.img-card:hover .hover-panel, .img-card:focus-within .hover-panel { opacity: 1; }
.hp-meta { margin-bottom: var(--sp-3); }
.hp-name { font-size: var(--fs-12); font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hp-sub { font-size: var(--fs-11); opacity: .85; margin-top: var(--sp-1); }
.hp-actions { display: flex; gap: var(--sp-3); justify-content: flex-end; flex-wrap: wrap; pointer-events: auto; }
/* 文本按钮默认 ~11px 水平内边距造成大段视觉空白,收敛后行内间距由 gap 控制(R1) */
.hp-actions :deep(.el-button) { padding: 4px 2px; }
.hp-actions .el-button { color: #fff; }
/* 置灰态（本地参考图缓存失效）：scoped 规则优先级高于 element 默认 disabled 色，需显式回退 */
.hp-actions .el-button.is-disabled { color: rgba(255,255,255,.4); }
.regen-wrap { display: inline-flex; }
/* hover 层标签行:点击筛选 */
.hp-tags { display: flex; flex-wrap: wrap; gap: var(--sp-2); margin-top: var(--sp-2); }
.hp-tag { font-size: var(--fs-11); line-height: 1; padding: 3px var(--sp-3); border-radius: 999px; background: rgba(255,255,255,.22); color: #fff; cursor: pointer; pointer-events: auto; }
.hp-tag:hover { background: rgba(255,255,255,.38); }
</style>