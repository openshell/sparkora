<template>
  <div class="lib-toolbar">
    <div class="tb-group tb-primary">
      <el-upload :show-file-list="false" :before-upload="beforeUpload" :http-request="doUpload"
                 accept=".png,.jpg,.jpeg,.webp" multiple>
        <el-button type="primary" :loading="uploading" icon="Upload">上传图片</el-button>
      </el-upload>
      <el-button v-if="canEdit" type="primary" plain icon="MagicStick" @click="emit('ai-gen')">AI 生图</el-button>
      <!-- 上传标签预选(09-13 image-tags):上传与 AI 生图共读,不持久化;可新建/可清空 -->
      <el-select v-if="canEdit" v-model="presetTagsModel" multiple filterable allow-create default-first-option
                 clearable collapse-tags collapse-tags-tooltip placeholder="上传标签" class="tag-preset" size="default">
        <el-option v-for="t in tagOptionNames" :key="t" :label="t" :value="t" />
      </el-select>
    </div>
    <div class="tb-group tb-browse">
      <!-- 语义搜索模式(09-15 img-semantic-search 子B):自然语言找图,与下方精确筛选互斥 -->
      <el-tooltip content="语义搜索：用自然语言找图，如「比亚迪销量海报」" placement="top" :show-after="300">
        <el-button :type="semanticMode ? 'primary' : ''" :plain="!semanticMode" :icon="Aim"
                   @click="emit('toggle-semantic')">语义搜索</el-button>
      </el-tooltip>
      <el-input v-if="semanticMode" v-model="semanticQueryModel" clearable placeholder="如：比亚迪销量海报 / 出海签约现场" class="sem-input" size="default"
                :prefix-icon="Aim" @keyup.enter="emit('run-semantic')" @clear="emit('exit-semantic')" />
      <el-input v-else v-model="keywordModel" clearable placeholder="搜索文件名 / 提示词" class="kw-input" size="default"
                :prefix-icon="Search" @input="emit('keyword-input')" @clear="emit('filter-change')" />
      <template v-if="!semanticMode">
        <el-select v-model="sourceFilterModel" clearable placeholder="来源" class="src-filter" size="default" @change="emit('filter-change')">
          <el-option v-for="(label, val) in SOURCE_LABELS" :key="val" :label="label" :value="val" />
        </el-select>
        <el-select v-model="tagFilterModel" multiple filterable collapse-tags collapse-tags-tooltip clearable
                   placeholder="标签（可多选，AND）" class="tag-filter" size="default" @change="emit('filter-change')">
          <!-- 09-15 img-classify:标签按命名空间前缀分组（主题/年份/其他），受控主题与自由标签隔离 -->
          <el-option-group v-for="g in tagGroups" :key="g.name" :label="g.name">
            <el-option v-for="t in g.options" :key="t.name" :label="`${t.name} (${t.count})`" :value="t.name" />
          </el-option-group>
        </el-select>
        <el-select v-model="projectFilterModel" clearable placeholder="项目" class="proj-filter" size="default" @change="emit('filter-change')">
          <el-option label="全部 / 全局图" :value="''" />
          <el-option v-for="p in projects" :key="p.id" :label="`#${p.id} ${p.topic}`" :value="p.id" />
        </el-select>
      </template>
      <template v-else>
        <el-select v-model="semanticTagsModel" multiple filterable collapse-tags collapse-tags-tooltip clearable
                   placeholder="限定标签（可选，AND）" class="tag-filter" size="default" @change="emit('refresh-semantic')">
          <el-option-group v-for="g in tagGroups" :key="g.name" :label="g.name">
            <el-option v-for="t in g.options" :key="t.name" :label="`${t.name} (${t.count})`" :value="t.name" />
          </el-option-group>
        </el-select>
        <!-- 相关度门槛：太低掺噪、太高空结果；开放调节避免「搜了没结果」无从下手 -->
        <el-tooltip content="相关度门槛：越高越严（结果更少更准）；搜不到时调低" placement="top" :show-after="300">
          <el-select v-model="semanticMinScoreModel" class="score-filter" size="default" @change="emit('refresh-semantic')">
            <el-option label="宽松 0.15" :value="0.15" />
            <el-option label="默认 0.30" :value="0.3" />
            <el-option label="严格 0.45" :value="0.45" />
          </el-select>
        </el-tooltip>
        <el-button type="primary" :icon="Aim" :loading="semanticLoading" @click="emit('run-semantic')">搜索</el-button>
      </template>
      <el-tooltip content="紧凑 / 舒适密度" placement="top" :show-after="300">
        <el-button text :icon="density === 'compact' ? Menu : Grid" aria-label="切换网格密度" @click="emit('toggle-density')" />
      </el-tooltip>
      <el-button v-if="!semanticMode" text :icon="Refresh" aria-label="刷新" @click="emit('reload')" />
      <el-button v-if="canEdit && !selectMode && imagesLength && !semanticMode" text type="danger" plain @click="emit('enter-select')">批量管理</el-button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Aim, Search, Refresh, MagicStick, Menu, Grid } from '@element-plus/icons-vue'
import { SOURCE_LABELS } from '../../utils/imageDisplay'

/**
 * 图库工具条（左主操作 / 右浏览控制）。
 * 从 ImageLibrary.vue 抽出（09-27-split-monoliths，纯结构重构，零行为变化）。
 * 状态与动作全部经 v-model / emits 交父；上传校验与请求由父以函数传入（复用既有实现）。
 */
const props = defineProps({
  canEdit: { type: Boolean, default: false },
  uploading: { type: Boolean, default: false },
  keyword: { type: String, default: '' },
  sourceFilter: { type: String, default: '' },
  tagFilter: { type: Array, default: () => [] },
  projectFilter: { type: [String, Number], default: '' },
  projects: { type: Array, default: () => [] },
  tagGroups: { type: Array, default: () => [] },
  tagOptionNames: { type: Array, default: () => [] },
  presetTags: { type: Array, default: () => [] },
  semanticMode: { type: Boolean, default: false },
  semanticQuery: { type: String, default: '' },
  semanticTags: { type: Array, default: () => [] },
  semanticMinScore: { type: Number, default: 0.3 },
  semanticLoading: { type: Boolean, default: false },
  density: { type: String, default: 'cozy' },
  selectMode: { type: Boolean, default: false },
  imagesLength: { type: Number, default: 0 },
  beforeUpload: { type: Function, required: true },
  doUpload: { type: Function, required: true }
})
const emit = defineEmits([
  'update:keyword', 'update:sourceFilter', 'update:tagFilter', 'update:projectFilter', 'update:presetTags',
  'update:semanticQuery', 'update:semanticTags', 'update:semanticMinScore',
  'keyword-input', 'filter-change', 'toggle-semantic', 'run-semantic', 'exit-semantic', 'refresh-semantic',
  'toggle-density', 'reload', 'ai-gen', 'enter-select'
])

// 具名 v-model 桥接（保持父持有状态；本组件不复制内部态）
const keywordModel = computed({ get: () => props.keyword, set: (v) => emit('update:keyword', v) })
const sourceFilterModel = computed({ get: () => props.sourceFilter, set: (v) => emit('update:sourceFilter', v) })
const tagFilterModel = computed({ get: () => props.tagFilter, set: (v) => emit('update:tagFilter', v) })
const projectFilterModel = computed({ get: () => props.projectFilter, set: (v) => emit('update:projectFilter', v) })
const presetTagsModel = computed({ get: () => props.presetTags, set: (v) => emit('update:presetTags', v) })
const semanticQueryModel = computed({ get: () => props.semanticQuery, set: (v) => emit('update:semanticQuery', v) })
const semanticTagsModel = computed({ get: () => props.semanticTags, set: (v) => emit('update:semanticTags', v) })
const semanticMinScoreModel = computed({ get: () => props.semanticMinScore, set: (v) => emit('update:semanticMinScore', v) })
</script>

<style scoped>
/* 工具条两段式 */
.lib-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.tb-group { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.tb-primary { flex: none; }
.tb-browse { flex: 1; min-width: 0; justify-content: flex-end; }
.kw-input { width: 220px; }
.sem-input { width: 260px; }
.src-filter { width: 110px; }
.tag-filter { width: 150px; }
.score-filter { width: 130px; }
.tag-preset { width: 180px; }
.proj-filter { width: 180px; }

@media (max-width: 768px) {
  .tb-browse { justify-content: flex-start; }
  .kw-input { width: 100%; }
  .sem-input { width: 100%; }
  .tag-filter, .tag-preset, .src-filter, .proj-filter, .score-filter { width: calc(50% - 5px); }
  .lib-toolbar .el-button { min-height: 44px; }
}
</style>
