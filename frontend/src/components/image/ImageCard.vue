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
    <!-- 移动端:常显文件名一行 + 标签 + ··· 更多 -->
    <div class="mobile-bar">
      <span class="m-name">{{ img.fileName }}</span>
      <el-dropdown trigger="click" @command="(cmd) => emit('mobile-cmd', cmd, img)">
        <el-button size="small" text aria-label="更多操作"><el-icon><MoreFilled /></el-icon></el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-if="canEdit" command="tags">编辑标签</el-dropdown-item>
            <el-dropdown-item v-if="isAiImage(img) && canEdit" command="regen" divided
                              :disabled="!canRegenerate">重新生成</el-dropdown-item>
            <el-dropdown-item v-if="canEdit" command="delete" divided>删除</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
    <!-- 移动端常显标签行:溢出横向滚动,点标签筛选 -->
    <div v-if="img.tags && img.tags.length" class="m-tags">
      <span v-for="t in img.tags" :key="t" class="m-tag" @click.stop="emit('filter-tag', t)">{{ t }}</span>
    </div>
    <!-- 移动端来源追溯行（09-15 img-classify，hover 不可用，常显） -->
    <div v-if="sourceInfo && sourceInfo.news" class="m-src"
         @click.stop="emit('open-news', sourceInfo)">
      来源：{{ sourceInfo.news.title }}
    </div>
    <!-- 移动端语义分数行（09-15 img-semantic-search，hover 不可用，常显） -->
    <div v-if="img.score != null" class="m-score">相关度 {{ (img.score * 100).toFixed(0) }}%</div>
  </div>
</template>

<script setup>
import { Check, MoreFilled } from '@element-plus/icons-vue'
import { imgUrl, originUrl, sourceLabel, shortTime, isAiImage, hpSubText } from '../../utils/imageDisplay'

/**
 * 图库卡片（缩略图 + hover 桌面元数据/操作 + 移动端常显行）。
 * 从 ImageLibrary.vue 抽出（09-27-split-monoliths，纯结构重构，零行为变化）。
 * 纯声明式：所有动作经 emits 交父执行，组件不直接改宿主状态。
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
const emit = defineEmits(['card-click', 'filter-tag', 'edit-tags', 'regen', 'delete', 'open-news', 'mobile-cmd'])

const shortDay = (t) => (t ? String(t).replace('T', ' ').slice(0, 10) : '')
</script>

<style scoped>
/* 卡片:瘦身,元数据入 hover 层 */
.img-card { position: relative; border-radius: var(--radius-sm); overflow: hidden; background: var(--paper); cursor: pointer; transition: box-shadow .2s; }
.img-card:hover { box-shadow: var(--shadow-hover); }
.img-card.selected { outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
.img-card.highlight { animation: hl-pulse 2s ease-out; }
@keyframes hl-pulse {
  0% { box-shadow: 0 0 0 4px var(--el-color-primary); }
  100% { box-shadow: 0 0 0 12px transparent; }
}
@media (prefers-reduced-motion: reduce) {
  .img-card { transition: none; }
  .img-card.highlight { animation: none; box-shadow: 0 0 0 4px var(--el-color-primary); }
}
.img-thumb { width: 100%; aspect-ratio: 4 / 3; display: block; background: var(--paper); }

/* 来源小标:色点 + 文字 */
.src-tag { position: absolute; top: 8px; left: 8px; display: inline-flex; align-items: center; gap: 4px; padding: 3px 8px 3px 6px; border-radius: 999px; background: rgba(0,0,0,.55); color: #fff; font-size: 10px; line-height: 1; backdrop-filter: blur(4px); pointer-events: none; }
.src-tag-dot { width: 6px; height: 6px; border-radius: 50%; flex: none; }
.src-tag.src-upload .src-tag-dot { background: #67c23a; }
.src-tag.src-ai-text2img .src-tag-dot { background: #409eff; }
.src-tag.src-ai-img2img .src-tag-dot { background: #9b59b6; }
.src-tag.src-byd .src-tag-dot { background: #e6a23c; }
.src-tag.src-byd-news .src-tag-dot { background: #f56c6c; }

/* 选择模式 checkbox */
.check-box { position: absolute; top: 8px; right: 8px; width: 22px; height: 22px; border-radius: 6px; background: rgba(255,255,255,.9); border: 1.5px solid var(--line); display: flex; align-items: center; justify-content: center; color: transparent; }
.check-box.checked { background: var(--el-color-primary); border-color: var(--el-color-primary); color: #fff; }

/* hover 层:桌面元数据 + 操作 */
.hover-panel { position: absolute; left: 0; right: 0; bottom: 0; padding: 24px 10px 8px; background: linear-gradient(to top, rgba(0,0,0,.78), rgba(0,0,0,.45) 70%, transparent); color: #fff; opacity: 0; transition: opacity .2s; pointer-events: none; }
.img-card:hover .hover-panel { opacity: 1; pointer-events: auto; }
.hp-meta { margin-bottom: 6px; }
.hp-name { font-size: 12px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hp-sub { font-size: 11px; opacity: .85; margin-top: 2px; }
.hp-gen { font-size: 10px; opacity: .7; margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* hover 层语义分数行（09-15 img-semantic-search）：分数 + 嵌入原文截断，title 显示全文 */
.hp-score { font-size: 10px; opacity: .92; margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hp-score-why { opacity: .8; }
.hp-actions { display: flex; gap: 6px; justify-content: flex-end; flex-wrap: wrap; }
.hp-actions .el-button { color: #fff; }
/* 置灰态（本地参考图缓存失效）：scoped 规则优先级高于 element 默认 disabled 色，需显式回退 */
.hp-actions .el-button.is-disabled { color: rgba(255,255,255,.4); }
.regen-wrap { display: inline-flex; }
/* hover 层标签行:点击筛选 */
.hp-tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 5px; }
.hp-tag { font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 999px; background: rgba(255,255,255,.22); color: #fff; cursor: pointer; pointer-events: auto; }
.hp-tag:hover { background: rgba(255,255,255,.38); }
/* hover 层来源追溯行（09-15 img-classify）：点击跳新闻原文 */
.hp-src { margin-top: 4px; }
.hp-src-text { display: inline-block; max-width: 100%; font-size: 10px; opacity: .92; cursor: pointer; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; pointer-events: auto; text-decoration: underline dotted; }
.hp-src-text:hover { opacity: 1; }

/* 移动端:常显文件名 + ··· 更多 */
.mobile-bar { display: none; }
.m-tags { display: none; }
.m-src { display: none; }
.m-score { display: none; }

/* 移动端:hover 不可用,常显文件名行 + 标签 + ··· */
@media (max-width: 768px) {
  .hover-panel { display: none; }
  .mobile-bar { display: flex; align-items: center; justify-content: space-between; gap: 4px; padding: 4px 6px 2px; background: var(--card); }
  .m-name { font-size: 11px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .mobile-bar .el-button { min-height: 44px; min-width: 44px; }
  /* 移动端常显标签行:单行横向滚动,点标签筛选 */
  .m-tags { display: flex; gap: 4px; padding: 0 6px 6px; background: var(--card); overflow-x: auto; flex-wrap: nowrap; scrollbar-width: none; }
  .m-tags::-webkit-scrollbar { display: none; }
  .m-tag { flex: none; display: inline-flex; align-items: center; min-height: 44px; font-size: 11px; line-height: 1; padding: 0 10px; border-radius: 999px; background: var(--paper); border: 1px solid var(--line); color: var(--muted); }
  /* 移动端来源行（09-15 img-classify）：常显单行省略，点击跳新闻原文（触控目标 ≥44px）。
     用 block + line-height 而非 flex——flex 容器上的 text-overflow 对匿名 flex item 不生效，
     标题超长会被硬裁而无「…」；block 才能正常省略。 */
  .m-src { display: block; min-height: 44px; line-height: 44px; padding: 0 6px; background: var(--card); font-size: 11px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  /* 移动端语义分数行（09-15 img-semantic-search）：常显 */
  .m-score { display: block; padding: 2px 6px 6px; background: var(--card); font-size: 11px; color: var(--muted); }
}
</style>
