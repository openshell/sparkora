<template>
  <el-dialog :model-value="modelValue" title="批量打标签" width="420px" class="tag-dialog"
             @update:model-value="(v) => emit('update:modelValue', v)">
    <div class="tag-dialog-tip">
      <span class="muted-small">对已选 {{ selectedCount }} 张图片统一操作</span>
    </div>
    <el-radio-group :model-value="action" class="bulk-tag-mode" @update:model-value="(v) => emit('update:action', v)">
      <el-radio value="add">补打标签</el-radio>
      <el-radio value="remove">移除标签</el-radio>
    </el-radio-group>
    <el-select :model-value="tags" multiple filterable allow-create default-first-option
               placeholder="选择或输入标签后回车" class="tag-dialog-select"
               @update:model-value="(v) => emit('update:tags', v)">
      <el-option v-for="t in tagOptionNames" :key="t" :label="t" :value="t" />
    </el-select>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="emit('confirm')">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 批量打标/移除对话框（09-13 image-tags）。
 * 从 ImageLibrary.vue 抽出（09-27-split-monoliths，纯结构重构，零行为变化）。
 */
defineProps({
  modelValue: { type: Boolean, default: false },
  selectedCount: { type: Number, default: 0 },
  tags: { type: Array, default: () => [] },
  action: { type: String, default: 'add' },
  tagOptionNames: { type: Array, default: () => [] },
  saving: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'update:tags', 'update:action', 'confirm'])
</script>

<style scoped>
.tag-dialog-tip { margin-bottom: 8px; }
.tag-dialog-select { width: 100%; }
.bulk-tag-mode { margin-bottom: 10px; }
.muted-small { color: var(--muted); font-size: 12px; }
</style>
