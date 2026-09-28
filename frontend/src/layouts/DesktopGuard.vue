<template>
  <!-- PC-only 遮罩:低于 --min-app-width 时全屏不透明覆盖,不做重排降级 -->
  <div class="desktop-guard" role="alert">
    <div class="guard-card">
      <div class="guard-title serif">请使用桌面浏览器访问</div>
      <div class="guard-msg">Sparkora 创作工作台为 PC 端设计，建议窗口宽度至少 1280px 以获得完整体验。</div>
    </div>
  </div>
</template>

<script setup>
// 纯 CSS 驱动,无脚本逻辑
</script>

<style scoped>
.desktop-guard {
  display: none;
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: var(--paper);
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 24px;
}
.guard-title { font-size: var(--fs-22); font-weight: 600; color: var(--ink); margin-bottom: var(--sp-4); }
.guard-msg { font-size: var(--fs-14); color: var(--muted); line-height: var(--lh-14); }

@media (max-width: 1279px) {
  .desktop-guard { display: flex; }
  body { overflow: hidden; }
}
</style>