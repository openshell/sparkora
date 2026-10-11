<template>
  <!-- 全屏预览宿主(10-10-img-gallery-ui-polish):单实例 viewer + 右侧信息面板。
       包裹层 position:fixed 形成独立叠层上下文;viewer 在内渲染(z-index 内联 ~2000),
       面板/入口按钮 z-index 2020 高于背景罩但让开 EP 内置控件区(关闭钮 top/right 40、
       右箭头 right:40 中部、底部工具条居中)——面板内缩 96px 悬浮卡不遮任何操作。 -->
  <div v-if="visible" class="preview-viewer">
    <el-image-viewer
      :url-list="urlList"
      :initial-index="clampedIndex"
      :teleported="false"
      hide-on-click-modal
      @switch="onViewerSwitch"
      @close="emit('update:visible', false)" />
    <!-- 面板收起后的纯看图模式:左上角重开入口(EP viewer 缩放/旋转/下载/关闭均不损失) -->
    <transition name="vi-fade">
      <button v-if="!panelOpen" type="button" class="info-fab" @click="panelOpen = true">
        <el-icon :size="14"><InfoFilled /></el-icon>详情
      </button>
    </transition>
    <!-- 右侧信息面板(可收起):行由 utils/imageDisplay.viewerRowsOf 拼装,全部非空才渲染,缺失不臆断 -->
    <transition name="vi-slide">
      <aside v-if="panelOpen" class="viewer-info">
        <div class="vi-head">
          <span class="vi-title">图片详情</span>
          <button type="button" class="vi-collapse" title="收起详情面板（纯看图）" aria-label="收起详情面板"
                  @click="panelOpen = false">
            <el-icon :size="14"><Close /></el-icon>
          </button>
        </div>
        <div class="vi-body">
          <template v-if="v.rows.length || v.tags.length || v.score != null || v.news">
            <div v-for="r in v.rows" :key="r.key" class="vi-row">
              <h3 v-if="r.key === 'name'" class="vi-name" :title="r.value">{{ r.value }}</h3>
              <template v-else-if="r.full">
                <div class="vi-label">{{ r.name }}</div>
                <pre class="vi-prompt">{{ r.value }}</pre>
              </template>
              <p v-else class="vi-text">{{ r.value }}</p>
            </div>
            <!-- 标签 pills:只展示不交互(筛选在列表层操作) -->
            <div v-if="v.tags.length" class="vi-tags">
              <span v-for="t in v.tags" :key="t" class="vi-tag">{{ t }}</span>
            </div>
            <!-- 语义相关度(语义模式):分数 + 命中原文;浏览态恒无 -->
            <div v-if="v.score != null" class="vi-score">
              <span class="vi-pct">相关度 {{ (v.score * 100).toFixed(0) }}%</span>
              <span v-if="v.scoreText" class="vi-why" :title="v.scoreText">命中：{{ v.scoreText }}</span>
            </div>
            <!-- 来源追溯(09-15 img-classify):点击跳新闻原文 -->
            <button v-if="v.news" type="button" class="vi-news" :title="v.news.title"
                    @click="emit('open-news', sourceInfo)">
              来源：{{ v.news.title }} · {{ shortDay(v.news.publishDate) }} ↗
            </button>
          </template>
          <p v-else class="vi-empty">该图暂无可用元数据</p>
        </div>
      </aside>
    </transition>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { Close, InfoFilled } from '@element-plus/icons-vue'
import { viewerRowsOf, shortDay } from '../../utils/imageDisplay'

/**
 * 全屏预览（10-10-img-gallery-ui-polish）：el-image-viewer 单实例宿主 + 右侧信息面板。
 * 纯声明式（props in / emits out，不得改宿主状态）：
 * - 原图 URL 约定不破坏：urlList 用原图 url 序列（thumbUrl 仅网格缩略）；
 * - @switch(newIndex) 上抛 → 宿主同步当前索引 → 面板信息跟随当前图；
 * - 面板显隐为组件内部状态（不持久化）；每次打开 viewer 重置为展示（新浏览会话默认带详情）；
 * - el-image-viewer 经 app.use(ElementPlus) 全局注册（main.js），无需显式 import。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 当前页原图 URL 序列（连续预览序列） */
  urlList: { type: Array, default: () => [] },
  /** 当前打开的图（v-for 索引直传，宿主已保证 [0, len)） */
  index: { type: Number, default: 0 },
  /** 当前图对象（浏览态实体或语义命中 ImageSearchHit 子集） */
  img: { type: Object, default: null },
  /** 当前图来源追溯（imageApi.getSource 结果；非新闻图为 null/undefined） */
  sourceInfo: { type: Object, default: null },
  /** 项目清单（面板「项目」段回显用） */
  projects: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:visible', 'switch', 'open-news'])

/** 面板行拼装（文件名/基础行/创建/生成信息/Prompt 全文/标签/相关度/来源追溯） */
const v = computed(() => viewerRowsOf(props.img, props.projects, props.sourceInfo))

/** 索引兜底 clamp（EP 内部已防越界，双保险避免宿主异常索引击穿 initial-index） */
const clampedIndex = computed(() =>
  Math.min(Math.max(props.index ?? 0, 0), Math.max((props.urlList?.length || 1) - 1, 0)))

const onViewerSwitch = (i) => emit('switch', i)

// 面板显隐：组件内部状态；每次打开 viewer 重置为展示（收起只作用于本次浏览会话）
const panelOpen = ref(true)
watch(() => props.visible, (vis) => { if (vis) panelOpen.value = true })
</script>

<style scoped>
.preview-viewer { position: fixed; inset: 0; z-index: 2000; }

/* 面板重开入口:半透明深底白字(仿卡片角标),置于左上角让开 EP 关闭钮(右上) */
.info-fab { position: absolute; top: var(--sp-7); left: var(--sp-7); z-index: 2020; display: inline-flex; align-items: center; gap: var(--sp-3); padding: var(--sp-3) var(--sp-5); border-radius: 999px; border: 1px solid rgba(255,255,255,.35); background: rgba(0,0,0,.55); color: #fff; font-size: var(--fs-13); cursor: pointer; backdrop-filter: blur(4px); }
.info-fab:hover { background: rgba(0,0,0,.72); }
.info-fab:focus-visible { outline: none; box-shadow: var(--focus-ring); }

/* 右侧信息面板:悬浮卡,内缩让开 EP viewer 关闭钮(top/right 40)、右箭头(right 44 中部)与底部工具条 */
.viewer-info { position: absolute; top: 96px; right: 96px; bottom: 96px; width: 320px; z-index: 2020; display: flex; flex-direction: column; background: var(--card); color: var(--ink); border-radius: var(--radius-lg); box-shadow: var(--shadow-3); overflow: hidden; }
.vi-head { display: flex; align-items: center; justify-content: space-between; padding: var(--sp-5) var(--sp-5) var(--sp-4); border-bottom: 1px solid var(--line); }
.vi-title { font-size: var(--fs-13); font-weight: 600; }
.vi-collapse { width: 24px; height: 24px; display: inline-flex; align-items: center; justify-content: center; border: none; border-radius: var(--radius-sm); background: transparent; color: var(--muted); cursor: pointer; }
.vi-collapse:hover { background: var(--n-100); color: var(--ink); }
.vi-collapse:focus-visible { outline: none; box-shadow: var(--focus-ring); }
.vi-body { flex: 1; overflow: auto; padding: var(--sp-5); display: flex; flex-direction: column; gap: var(--sp-4); align-items: flex-start; }
.vi-name { margin: 0; font-size: var(--fs-14); font-weight: 600; line-height: var(--lh-14); word-break: break-all; }
.vi-label { font-size: var(--fs-11); color: var(--muted); margin-bottom: var(--sp-2); }
.vi-text { margin: 0; font-size: var(--fs-13); line-height: var(--lh-13); word-break: break-all; }
/* Prompt 全文:可滚动块,超长不出格 */
.vi-prompt { margin: 0; width: 100%; box-sizing: border-box; max-height: 160px; overflow: auto; white-space: pre-wrap; word-break: break-word; font-family: inherit; font-size: var(--fs-12); line-height: var(--lh-12); color: var(--ink); background: var(--n-50); border: 1px solid var(--line); border-radius: var(--radius-sm); padding: var(--sp-3) var(--sp-4); }
.vi-tags { display: flex; flex-wrap: wrap; gap: var(--sp-2); }
.vi-tag { font-size: var(--fs-11); line-height: 1; padding: 3px var(--sp-3); border-radius: 999px; background: var(--n-100); color: var(--ink); }
.vi-score { display: flex; flex-direction: column; gap: var(--sp-1); font-size: var(--fs-12); }
.vi-pct { color: var(--brand); font-weight: 600; }
.vi-why { font-size: var(--fs-11); line-height: var(--lh-11); color: var(--muted); overflow: hidden; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; }
.vi-news { max-width: 100%; text-align: left; border: none; background: transparent; padding: 0; color: var(--brand); font-size: var(--fs-12); line-height: var(--lh-12); cursor: pointer; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-decoration: underline dotted; }
.vi-news:hover { color: var(--brand-strong); }
.vi-news:focus-visible { outline: none; box-shadow: var(--focus-ring); border-radius: var(--radius-sm); }
.vi-empty { margin: 0; font-size: var(--fs-13); color: var(--muted); }

/* 面板开合过渡(reduced-motion 兜底关闭) */
.vi-slide-enter-active, .vi-slide-leave-active { transition: transform .2s ease, opacity .2s ease; }
.vi-slide-enter-from, .vi-slide-leave-to { transform: translateX(16px); opacity: 0; }
.vi-fade-enter-active, .vi-fade-leave-active { transition: opacity .2s ease; }
.vi-fade-enter-from, .vi-fade-leave-to { opacity: 0; }
@media (prefers-reduced-motion: reduce) {
  .vi-slide-enter-active, .vi-slide-leave-active,
  .vi-fade-enter-active, .vi-fade-leave-active { transition: none; }
}
</style>