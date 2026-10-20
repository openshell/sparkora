<template>
  <el-drawer :model-value="modelValue" title="配图" size="420px" class="img-drawer" :with-header="true"
             @update:model-value="(v) => emit('update:modelValue', v)">
    <el-tabs v-model="imgTab" class="img-tabs">
      <!-- 图库:全量图库选用(插入正文 / 设封面) -->
      <el-tab-pane label="图库" name="library">
        <div class="lib-filter-row">
          <el-select v-model="libSource" clearable placeholder="来源" size="small" class="lib-src" @change="reloadLibrary">
            <el-option v-for="(label, val) in SOURCE_LABELS" :key="val" :label="label" :value="val" />
          </el-select>
          <el-input v-model="libKeyword" clearable placeholder="搜文件名/提示词" size="small" class="lib-kw"
                    @input="onLibKeywordInput" @clear="reloadLibrary" />
        </div>
        <div v-if="!libraryImages.length" class="img-pop-empty">无匹配图片：到「图库」页上传，或用下方 AI 生成</div>
        <template v-else>
          <div class="img-pop-tip">
            {{ bydMode ? '点击图片注册为 BYD 媒体并插入 token；最终由本机 Agent 上传比亚迪 CDN。' : '点击图片插入到编辑器光标处；正文里没引用的插图不会出现在文章中' }}
          </div>
          <div class="img-pop-grid" v-infinite-scroll="loadMoreLibrary" :infinite-scroll-disabled="libLoading"
               :infinite-scroll-distance="80" :infinite-scroll-immediate-check="false">
            <div v-for="img in libraryImages" :key="img.id" class="img-pop-cell"
                 :class="{ inserted: insertedUrls.has(originUrl(img)) }">
              <div class="img-pop-thumb-wrap" @click="emit('insert', img)">
                <el-image :src="imgUrl(img)" fit="cover" class="img-pop-thumb" />
                <span v-if="!bydMode && img.id === coverImageId" class="img-pop-cover">封面</span>
                <span v-if="insertedUrls.has(originUrl(img))" class="img-pop-check">✓</span>
                <div class="img-pop-hover">
                  <el-icon><Plus /></el-icon> 插入正文
                </div>
              </div>
              <div v-if="!bydMode" class="img-pop-actions">
                <el-button size="small" :type="img.id === coverImageId ? 'success' : 'default'"
                           :disabled="img.id === coverImageId || busy" @click="emit('set-cover', img.id)">
                  {{ img.id === coverImageId ? '✓ 封面' : '设为封面' }}
                </el-button>
              </div>
            </div>
          </div>
          <div v-if="libLoading" class="lib-loading">加载中…</div>
          <div v-else-if="!libHasMore" class="lib-loading">已加载全部 {{ libTotal }} 张</div>
        </template>
      </el-tab-pane>
      <!-- AI 生图（09-26 image-gen-drawer-ux：共用 AiImageDrawer，preview 模式 → 插入正文/设封面）
           `model-value` 传「抽屉打开且停在 AI tab」：粘贴监听只在 AI 生图 tab 可见时生效。 -->
      <el-tab-pane label="AI 生图" name="ai">
        <AiImageDrawer :model-value="modelValue && imgTab === 'ai'" mode="preview" :project-id="projectId"
                       :show-cover-action="!bydMode"
                       @insert="(img) => emit('insert', img)" @set-cover="(id) => emit('set-cover', id)"
                       @generated="(list, meta) => emit('generated', list, meta)" />
      </el-tab-pane>
      <!-- 智能建议(09-15 article-auto-illustrate 子C):按段落锚点语义检索图库,产出**建议**。
           系统只给建议,点「插入到此段」/「全部采用」才会写入正文(无自动插入开关)。 -->
      <el-tab-pane label="智能建议" name="suggest">
        <div class="sug-head">
          <el-input-number v-model="sugMinScore" size="small" class="sug-score"
                           :min="0" :max="1" :step="0.05" :precision="2" controls-position="right" />
          <el-select v-model="sugTagFilter" multiple collapse-tags collapse-tags-tooltip clearable
                     placeholder="标签预过滤(可多选)" size="small" class="sug-tags">
            <el-option v-for="t in allTags" :key="t.name" :label="`${t.name} (${t.count})`" :value="t.name" />
          </el-select>
          <el-button type="primary" size="small" :loading="sugLoading" :disabled="busy" @click="generateSuggestions">
            {{ sugLoaded ? '重新生成' : '生成建议' }}
          </el-button>
        </div>
        <div class="img-pop-tip">
          {{ bydMode ? '建议图会先注册为 BYD 媒体，再插入正文 token；不会自动插图。' : '系统只给建议，点「插入到此段」或「全部采用」才会写入正文；不会自动插图。' }}
          门槛默认 0.4（对齐后端校准值），建议偏少时再调低。
        </div>

        <!-- 空态:未生成 -->
        <div v-if="!sugLoaded && !sugLoading" class="img-pop-empty">
          点「生成建议」，系统按正文段落语义匹配图库图片（仅建议，需你确认）。
        </div>
        <el-skeleton v-else-if="sugLoading" :rows="4" animated />
        <!-- 空态:生成后无候选 / 全部被忽略（区分文案:后者是用户主动忽略，不应再劝「调低门槛」） -->
        <div v-else-if="!sugGroups.length" class="img-pop-empty">
          <template v-if="sugDismissedCount">
            已忽略全部建议段落。若想重新看到建议，可调低门槛或换标签后再点「重新生成」。
          </template>
          <template v-else>
            本次没有匹配到合适配图。可尝试调低门槛、换标签预过滤，或先到「图库」页补充图片
            （图库越丰富，建议越有用）。
          </template>
        </div>
        <div v-else class="sug-list">
          <div v-for="g in sugGroups" :key="g.anchorKey" class="sug-group">
            <div class="sug-group-head">
              <span class="sug-group-title">{{ g.headingPath || '开头段落' }}</span>
              <span class="sug-group-actions">
                <el-button size="small" plain :disabled="busy" @click="onAdoptGroup(g)">全部采用</el-button>
                <el-button size="small" text :disabled="busy" @click="onDismissGroup(g)">忽略此段</el-button>
              </span>
            </div>
            <div class="sug-anchor-text" :title="g.anchorText">{{ g.anchorText }}</div>
            <!-- 横排卡(10-11 img-suggest-card-layout):左缩略图 + 右信息列;纯展示改动,事件接线/onAdoptOne 签名零变化 -->
            <div class="sug-grid">
              <div v-for="img in g.candidates" :key="img.imageId" class="sug-cell">
                <el-image :src="img.thumbUrl || img.url" fit="cover" class="sug-thumb"
                          :preview-src-list="[img.url]" preview-teleported hide-on-click-modal />
                <div class="sug-info">
                  <div class="sug-meta">
                    <span class="sug-score-val">相关度 {{ (img.score * 100).toFixed(0) }}%</span>
                    <span v-if="sugAdopted.has(img.imageId)" class="sug-adopted">已采用</span>
                  </div>
                  <div class="sug-tag-row">
                    <el-tag v-for="t in sugVisibleTags(img)" :key="t" size="small" effect="plain">{{ t }}</el-tag>
                    <el-tag v-if="sugExtraTagCount(img) > 0" size="small" effect="plain" class="sug-tag-more"
                            :title="sugExtraTagsTitle(img)">+{{ sugExtraTagCount(img) }}</el-tag>
                  </div>
                  <el-button size="small" type="primary" plain class="sug-insert"
                             :disabled="busy" @click="onAdoptOne(g, img)">插入到此段</el-button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </el-drawer>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import AiImageDrawer from '../AiImageDrawer.vue'
import { Plus } from '@element-plus/icons-vue'
import { usePreviewLibrary, SOURCE_LABELS } from '../../composables/usePreviewLibrary'
import { usePreviewSuggestions } from '../../composables/usePreviewSuggestions'

/**
 * 预览页「配图」抽屉（图库 / AI 生图 / 智能建议 三 tab）。
 * 从 StepPreview.vue 抽出（09-27-split-monoliths，纯结构重构，零行为变化）。
 *
 * 内部委托 usePreviewLibrary（分页）与 usePreviewSuggestions（建议）；
 * 写入正文由父通过 insert 事件 / insertAtAnchor prop 完成，本组件不直接改宿主状态。
 *
 * @prop busy 宿主共享忙碌标记（封面/建议互斥，保持原单标记语义）；内部变更经 update:busy 回写
 * @prop insertedUrls 正文已引用 URL 集合（图库 tab 「已插入」标记）
 * @prop snapshot 配图快照 ref（建议采用回滚判定读 bodyImageIds）
 * @prop insertAtAnchor (headingPath, md) => boolean（编辑器按标题插入）
 * @prop refreshSnapshot () => Promise（写入后刷新配图快照）
 * @prop saveContent () => Promise（persist:建议采纳结算后保存正文;透传宿主 StepPreview.saveContent,
 *       本组件不感知保存实现,与 refreshSnapshot 同款回调接线）
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  projectId: { type: [String, Number], default: null },
  busy: { type: Boolean, default: false },
  mediaMode: { type: String, default: 'gzh' },
  insertedUrls: { type: Object, default: () => new Set() },
  coverImageId: { type: [String, Number], default: null },
  snapshot: { type: Object, default: null },
  isEditorReady: { type: Function, default: null },
  insertAtAnchor: { type: Function, default: null },
  refreshSnapshot: { type: Function, default: null },
  saveContent: { type: Function, default: null },
  // BYD 模式由 BydEditorStep 注入：注册图库/AI 图片为 BYD 媒体，返回 { target, rollback }
  prepareMediaForInsertion: { type: Function, default: null }
})
const emit = defineEmits(['update:modelValue', 'update:busy', 'insert', 'set-cover', 'generated'])

const imgTab = ref('library')
const bydMode = computed(() => props.mediaMode === 'byd')

// 共享忙碌标记：父持有单 ref（封面操作与建议采用互斥）；此处以可写 computed 桥接
const busy = computed({
  get: () => props.busy,
  set: (v) => emit('update:busy', v)
})

// 图库图片图床公网 URL（入库即已转存，后端填充 url 字段）
const imgUrl = (img) => img?.thumbUrl || img?.url || ''   // S10:网格缩略图(imageView2/webp)
const originUrl = (img) => img?.url || ''                 // 插入正文/大图预览用原图 URL

// 智能建议横排卡展示辅助(10-11 img-suggest-card-layout;纯展示函数,无响应式依赖):
// 标签最多显示 2 个(≤2 全显),超出折叠为 +N 小标签,原生 title 提示剩余标签
const SUG_TAG_VISIBLE_MAX = 2
const sugVisibleTags = (img) => (img.tags || []).slice(0, SUG_TAG_VISIBLE_MAX)
const sugExtraTagCount = (img) => Math.max(0, (img.tags || []).length - SUG_TAG_VISIBLE_MAX)
const sugExtraTagsTitle = (img) => `其余标签：${(img.tags || []).slice(SUG_TAG_VISIBLE_MAX).join('、')}`

const {
  libraryImages, libTotal, libLoading, libSource, libKeyword, libHasMore,
  loadMoreLibrary, reloadLibrary, onLibKeywordInput
} = usePreviewLibrary()

// 智能建议组合函数契约要求 ref/computed（内部取 .value）；传普通函数会让 projectId 变 undefined
// → URL 拼成 /api/projects/undefined/… → 后端 400「参数 projectId 格式不正确」（10-11 实测回归）
const projectIdRef = computed(() => props.projectId)
const {
  sugGroups, sugLoading, sugLoaded, sugTagFilter, sugMinScore, sugAdopted, sugDismissedCount, allTags,
  generateSuggestions, loadSugTags, onAdoptOne, onAdoptGroup, onDismissGroup
} = usePreviewSuggestions(projectIdRef, {
  busy,
  getSnapshot: () => props.snapshot,
  isEditorReady: () => (props.isEditorReady ? props.isEditorReady() : !!props.insertAtAnchor),
  insertAtAnchor: (headingPath, md) => (props.insertAtAnchor ? props.insertAtAnchor(headingPath, md) : false),
  originUrl,
  refreshSnapshot: () => (props.refreshSnapshot ? props.refreshSnapshot() : Promise.resolve()),
  saveContent: () => (props.saveContent ? props.saveContent() : Promise.resolve()),
  // 未传回调时必须保持 null，否则组合函数会误判为 BYD 外部登记模式。
  prepareMediaForInsertion: bydMode.value && props.prepareMediaForInsertion
    ? (img) => props.prepareMediaForInsertion(img)
    : null
})

// S10:抽屉首次打开时拉图库分页(后续打开仅在空态时重拉,避免打断滚动位置)
watch(() => props.modelValue, (open) => { if (open && !libraryImages.value.length) reloadLibrary() })
// 智能建议 tab 首次进入时拉标签清单(仅可选预过滤;建议由用户点按钮触发生成,不自动请求)
watch(imgTab, (t) => { if (t === 'suggest') loadSugTags() })
</script>

<style scoped>
/* 配图抽屉(图库插入 + AI 生图——AI 生图样式见 components/AiImageDrawer.vue) */
.img-drawer :deep(.el-drawer__body) { padding: 0 16px 16px; }
.img-pop-tip { font-size: 12px; color: var(--muted); line-height: 1.6; margin-bottom: 8px; }
.img-pop-empty { font-size: 13px; color: var(--muted); padding: 8px 0; }
.img-tabs :deep(.el-tabs__header) { margin-bottom: 8px; }
.img-tabs :deep(.el-tabs__nav-wrap)::after { height: 1px; }
.img-pop-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; max-height: 60vh; overflow-y: auto; }
.lib-filter-row { display: flex; gap: 8px; margin-bottom: 10px; }
.lib-src { width: 110px; flex: none; }
.lib-kw { flex: 1; }
.lib-loading { text-align: center; color: var(--muted); font-size: 12px; padding: 10px 0; }
.img-pop-cell { position: relative; border: 1px solid var(--line); border-radius: 8px; overflow: hidden; transition: border-color .2s, box-shadow .2s; background: var(--card); }
.img-pop-cell:hover { border-color: var(--brand, var(--el-color-primary)); }
.img-pop-cell.inserted { border-color: var(--ok, #67c23a); box-shadow: 0 0 0 2px color-mix(in srgb, var(--ok, #67c23a) 18%, transparent); }
.img-pop-thumb-wrap { position: relative; cursor: pointer; }
.img-pop-thumb { width: 100%; aspect-ratio: 1; display: block; }
/* 悬浮「插入正文」提示(桌面 hover;移动端点击图片即插入) */
.img-pop-hover {
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; gap: 4px;
  background: rgba(0,0,0,.45); color: #fff; font-size: 13px; font-weight: 600;
  opacity: 0; transition: opacity .2s ease;
}
.img-pop-thumb-wrap:hover .img-pop-hover { opacity: 1; }
.img-pop-cover { position: absolute; left: 4px; top: 4px; min-width: 16px; height: 16px; line-height: 16px;
  text-align: center; font-size: 11px; border-radius: 8px; background: var(--ok, #67c23a); color: #fff; padding: 0 4px; }
.img-pop-check { position: absolute; right: 4px; top: 4px; min-width: 16px; height: 16px; line-height: 16px;
  text-align: center; font-size: 11px; border-radius: 8px; background: var(--ok, #67c23a); color: #fff; }
.img-pop-actions { padding: 6px; }
.img-pop-actions .el-button { width: 100%; min-height: 30px; margin: 0; }

/* 智能配图建议(09-15 article-auto-illustrate 子C):只给建议,点采用才写入 */
.sug-head { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; flex-wrap: wrap; }
.sug-score { width: 120px; flex: none; }
.sug-tags { flex: 1; min-width: 150px; }
.sug-list { max-height: 62vh; overflow-y: auto; }
.sug-group { border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 10px; margin-bottom: 12px; background: var(--card); }
.sug-group-head { display: flex; align-items: center; gap: 8px; justify-content: space-between; margin-bottom: 4px; }
.sug-group-title { font-size: 13px; font-weight: 700; color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sug-group-actions { flex: none; display: inline-flex; gap: 4px; }
.sug-anchor-text { font-size: 12px; color: var(--muted); line-height: 1.5; margin-bottom: 8px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 横排卡(10-11 img-suggest-card-layout):单列纵向排布,每卡 = 左缩略图(固定 100px,4:3) + 右信息列 */
.sug-grid { display: flex; flex-direction: column; gap: 10px; }
.sug-cell { display: flex; gap: 10px; border: 1px solid var(--line); border-radius: var(--radius-sm);
  padding: 6px; background: var(--card); }
.sug-thumb { width: 100px; flex: none; aspect-ratio: 4 / 3; border-radius: var(--radius-sm);
  background: var(--paper); align-self: center; }
.sug-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.sug-meta { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.sug-score-val { font-size: 11px; color: var(--muted); flex: none; }
.sug-adopted { font-size: 11px; color: #fff; background: var(--ok, #67c23a); border-radius: 8px; padding: 0 6px; flex: none; }
.sug-tag-row { display: flex; flex-wrap: nowrap; align-items: center; gap: 4px; min-height: 20px; overflow: hidden; }
.sug-tag-row .el-tag { min-width: 0; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sug-tag-row .sug-tag-more { flex: none; }
.sug-insert { width: 100%; min-height: var(--control-h-sm); }
</style>
