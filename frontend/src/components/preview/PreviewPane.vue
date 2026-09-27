<template>
  <div class="pane pane-right">
    <div class="pane-head">
      <span>公众号预览</span>
      <el-tag v-if="renderError" type="danger" size="small" effect="plain">
        {{ renderError }}
        <el-button link size="small" @click="emit('retry')">重试</el-button>
      </el-tag>
      <el-icon v-else-if="rendering" class="spin"><Loading /></el-icon>
    </div>
    <!-- 主题/渲染进度条(150ms 细条) -->
    <div class="theme-progress" :class="{ active: rendering || themeLoading }" aria-hidden="true"></div>
    <div class="phone" :class="`w-${previewWidth}`">
      <div class="phone-device">
        <div class="phone-status">
          <span class="status-time">9:41</span>
          <span class="status-icons">
            <svg width="17" height="11" viewBox="0 0 17 11" fill="currentColor" aria-hidden="true"><path d="M12.5 3.8a5.4 5.4 0 0 0-8 0l1.1 1.2a3.8 3.8 0 0 1 5.8 0l1.9-1.2ZM9.9 6.4a2.2 2.2 0 0 0-2.8 0L8.5 8.2l1.4-1.8Z"/><rect x="0" y="8.4" width="2" height="2.4" rx="0.5"/><rect x="3" y="6.4" width="2" height="4.4" rx="0.5"/><rect x="6" y="4.4" width="2" height="6.4" rx="0.5"/><rect x="9" y="2.4" width="2" height="8.4" rx="0.5"/></svg>
            <svg width="25" height="12" viewBox="0 0 25 12" fill="none" aria-hidden="true"><rect x="0.5" y="0.5" width="21" height="11" rx="3" stroke="currentColor" opacity="0.5"/><rect x="2" y="2" width="16" height="8" rx="1.8" fill="currentColor"/><path d="M23 4v4c1-.3 1.6-1 1.6-2S24 4.3 23 4Z" fill="currentColor" opacity="0.5"/></svg>
          </span>
        </div>
        <div ref="previewBody" class="wechat-body" @scroll="emit('scroll')">
          <div v-if="html" class="wenyan-preview" v-html="html"></div>
          <el-skeleton v-else :rows="9" animated class="preview-skeleton" />
        </div>
        <div class="phone-home" aria-hidden="true"><span></span></div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { Loading } from '@element-plus/icons-vue'

/**
 * 预览页右侧手机拟真预览窗（渲染态/错误重试/滚动）。
 * 从 StepPreview.vue 抽出（09-27-split-monoliths，纯结构重构，零行为变化）。
 * 滚动同步由父编排：父调 scrollToPercent(percent)，滚动时 emit('scroll') 由父读 currentPercent()。
 */
const props = defineProps({
  html: { type: String, default: '' },
  rendering: { type: Boolean, default: false },
  themeLoading: { type: Boolean, default: false },
  renderError: { type: String, default: '' },
  previewWidth: { type: String, default: 'phone' }
})
const emit = defineEmits(['scroll', 'retry'])

const previewBody = ref(null)
/** 按百分比滚动预览体（父编辑器滚动 → 预览同步）。 */
const scrollToPercent = (percent) => {
  const el = previewBody.value
  if (el) el.scrollTop = percent * (el.scrollHeight - el.clientHeight)
}
/** 当前预览体滚动百分比（预览滚动 → 编辑器同步）。 */
const currentPercent = () => {
  const el = previewBody.value
  if (!el) return 0
  return (el.scrollTop) / Math.max(1, el.scrollHeight - el.clientHeight)
}
defineExpose({ scrollToPercent, currentPercent })
</script>

<style scoped>
.pane { border: 1px solid var(--line); border-radius: var(--radius-sm); overflow: hidden; background: var(--paper); display: flex; flex-direction: column; }
.pane-head { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-bottom: 1px solid var(--line); font-size: 12px; font-weight: 600; color: var(--muted); background: var(--el-fill-color-light); }

/* ===== 手机拟真(参照 iPhone 外观;背景渐变模拟桌面环境) ===== */
.phone { background: linear-gradient(160deg, #f0eee9 0%, #e7e3db 100%); padding: 20px 0; display: flex; justify-content: center; flex: 1; }
/* 宽度档位:phone 430 / tablet 720 / full 100%(仅视觉容器宽度,不改渲染内容) */
.phone.w-tablet .phone-device { width: 720px; max-width: 100%; }
.phone.w-full .phone-device { width: 100%; max-width: 100%; border-radius: 14px; padding: 6px 10px 8px; }
.phone.w-full .phone-status, .phone.w-full .phone-home { display: none; }
.phone.w-full .wechat-body { border-radius: 10px; }
.phone-device {
  width: 430px; max-width: 96%;
  background: #fff; border-radius: 28px; padding: 6px 10px 8px;
  border: 1px solid rgba(0,0,0,.06);
  box-shadow: 0 0 0 2px #2c2c2e, 0 1px 3px rgba(0,0,0,.18), var(--shadow-hover);
  display: flex; flex-direction: column; max-height: 100%;
}
.phone-status { display: flex; align-items: center; justify-content: space-between; padding: 4px 14px 2px; color: #1a1a1a; }
.status-time { font-size: 12px; font-weight: 600; letter-spacing: .2px; font-family: -apple-system, "SF Pro Text", "PingFang SC", sans-serif; }
.status-icons { display: inline-flex; align-items: center; gap: 5px; }
.status-icons svg { display: block; opacity: .9; }
.wechat-body { width: 100%; background: #fff; padding: 10px 14px 20px; min-height: 520px; max-height: 640px; overflow: auto; border-radius: 0 0 14px 14px; overflow-y: auto; }
.phone-home { display: flex; justify-content: center; padding: 5px 0 3px; }
.phone-home span { width: 100px; height: 4px; border-radius: 2px; background: rgba(0,0,0,.28); }
.wenyan-preview { animation: fadein .18s ease; }
@keyframes fadein { from { opacity: 0; } to { opacity: 1; } }
.preview-skeleton { padding: 16px; }

/* 主题/渲染进度条:双栏头部下侧的细条 */
.theme-progress { height: 2px; position: relative; overflow: hidden; background: transparent; }
.theme-progress::before { content: ""; position: absolute; inset: 0; width: 40%; background: var(--brand); opacity: 0; transition: opacity .15s ease; }
.theme-progress.active::before { opacity: .85; animation: progress-slide 1s ease-in-out infinite; }
@keyframes progress-slide { 0% { transform: translateX(-100%); } 100% { transform: translateX(350%); } }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 900px) {
  .phone-device,
  .phone.w-tablet .phone-device,
  .phone.w-full .phone-device { width: 100%; max-width: 430px; }
}
@media (prefers-reduced-motion: reduce) {
  .wenyan-preview { animation: none; }
  .theme-progress { display: none; }
}
</style>
