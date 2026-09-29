/**
 * 主从分栏的列宽状态机:可拖拽 + 方向键微调,记忆到 localStorage。
 * 批 2 从 StepPreview 的分栏交互抽成共用(预览按百分比、问答按像素,故 min/max 单位不同)。
 *
 * 用法:
 *   const { containerRef, dragging, size, onPointerDown, onKey } = useSplitPane({
 *     storageKey: 'sparkora.qaSideWidth', initial: 280, min: 200, max: 480, unit: 'px'
 *   })
 * 模板里 <div ref="containerRef" class="pane" :style="{ flex: `0 0 ${size}px` }"> + 分隔条
 * @pointerdown="onPointerDown" @keydown="onKey"。
 *
 * 注意:StepPreview 目前仍用自己的内联实现(百分比语义),后续批次可整体切到本 composable;
 * 本批只服务 QaChat,不动 StepPreview 以免影响其渲染链路。
 */
import { ref, onBeforeUnmount } from 'vue'

export function useSplitPane({ storageKey, initial = 280, min = 200, max = 480, unit = 'px' }) {
  const clamp = (v) => Math.min(max, Math.max(min, v))
  const read = () => {
    const raw = Number(localStorage.getItem(storageKey))
    return clamp(Number.isFinite(raw) && raw > 0 ? raw : initial)
  }

  const containerRef = ref(null)   // 整个分栏容器(指针 x → 列宽的换算基准)
  const dragging = ref(false)
  const size = ref(read())

  const persist = () => localStorage.setItem(storageKey, String(Math.round(size.value * 10) / 10))

  const onPointerMove = (e) => {
    const box = containerRef.value
    if (!dragging.value || !box) return
    const rect = box.getBoundingClientRect()
    if (!rect.width) return
    // 百分比分栏:指针相对容器的占比;像素分栏:指针距容器左沿的距离
    size.value = clamp(unit === 'px' ? e.clientX - rect.left : ((e.clientX - rect.left) / rect.width) * 100)
  }
  const stop = () => {
    if (!dragging.value) return
    dragging.value = false
    window.removeEventListener('pointermove', onPointerMove)
    window.removeEventListener('pointerup', stop)
    persist()
  }
  const onPointerDown = (e) => {
    dragging.value = true
    window.addEventListener('pointermove', onPointerMove)
    window.addEventListener('pointerup', stop)
    e.preventDefault()
  }
  /** 键盘可达:方向键微调(Shift 加速);Home/End 收到两端。 */
  const onKey = (e) => {
    const step = e.shiftKey ? (unit === 'px' ? 20 : 10) : (unit === 'px' ? 8 : 2)
    if (e.key === 'ArrowLeft') size.value = clamp(size.value - step)
    else if (e.key === 'ArrowRight') size.value = clamp(size.value + step)
    else if (e.key === 'Home') size.value = min
    else if (e.key === 'End') size.value = max
    else return
    persist()
  }

  // 拖拽途中卸载(切页/关抽屉)会漏掉 pointerup,补一次清理
  onBeforeUnmount(stop)

  return { containerRef, dragging, size, onPointerDown, onKey }
}
