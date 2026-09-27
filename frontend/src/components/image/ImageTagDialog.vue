<template>
  <el-dialog :model-value="modelValue" title="编辑标签" width="420px" class="tag-dialog"
             @update:model-value="(v) => emit('update:modelValue', v)">
    <div class="tag-dialog-tip">
      <span class="muted-small">为「{{ image?.fileName }}」设置标签</span>
    </div>
    <el-select :model-value="tags" multiple filterable allow-create default-first-option
               placeholder="选择或输入标签后回车" class="tag-dialog-select"
               @update:model-value="(v) => emit('update:tags', v)">
      <el-option v-for="t in tagOptionNames" :key="t" :label="t" :value="t" />
    </el-select>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="emit('save')">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 单图编辑标签对话框（09-13 image-tags：全量覆盖语义）。
 * 从 ImageLibrary.vue 抽出（09-27-split-monoliths，纯结构重构，零行为变化）。
 */
defineProps({
  modelValue: { type: Boolean, default: false },
  image: { type: Object, default: null },
  tags: { type: Array, default: () => [] },
  tagOptionNames: { type: Array, default: () => [] },
  saving: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'update:tags', 'save'])
</script>

<style scoped>
.tag-dialog-tip { margin-bottom: 8px; }
.tag-dialog-select { width: 100%; }
.muted-small { color: var(--muted); font-size: 12px; }
</style>
