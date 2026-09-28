/**
 * 页面 → 上下文条通道。
 * AppShell 顶层 provide 一个 reactive 壳,页面在 setup 里注入后写 crumbs / actions,
 * 由外壳的 topbar 统一渲染。未提供该注入的页面(批 2/3 迁移前)inject 到 null,
 * 继续渲染自己的 page-header,互不冲突。
 */
import { provide, inject, reactive } from 'vue'

export const PAGE_HEADER_KEY = Symbol('sparkora.pageHeader')

export function providePageHeader() {
  const header = reactive({ crumbs: [], actions: [] })
  provide(PAGE_HEADER_KEY, header)
  return header
}

/** 返回 reactive { crumbs, actions } 或 null(该页未参与上下文条) */
export function usePageHeader() {
  return inject(PAGE_HEADER_KEY, null)
}