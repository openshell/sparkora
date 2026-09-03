import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// 开发期前端代理 /api 到后端；端口须与 .env 的 SERVER_PORT 保持一致
export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] })
  ],
  // 启动时一次性完成依赖预构建,避免 dev 运行中发现新依赖触发
  // 「optimized dependencies changed. reloading」整页 reload(历史上切步骤/刷新白屏的干扰源之一)
  // 列表 = 模板实际用到的 <el-*> 组件 + ElMessage + select 内部依赖(tooltip/popper/overlay),均经 node_modules 核实存在
  optimizeDeps: {
    include: [
      'vue',
      'vue-router',
      'pinia',
      'axios',
      'markdown-it',
      'element-plus',
      'element-plus/es',
      'element-plus/es/components/base/style/css',
      'element-plus/es/components/alert/style/css',
      'element-plus/es/components/button/style/css',
      'element-plus/es/components/card/style/css',
      'element-plus/es/components/checkbox/style/css',
      'element-plus/es/components/checkbox-group/style/css',
      'element-plus/es/components/col/style/css',
      'element-plus/es/components/dialog/style/css',
      'element-plus/es/components/empty/style/css',
      'element-plus/es/components/form/style/css',
      'element-plus/es/components/form-item/style/css',
      'element-plus/es/components/icon/style/css',
      'element-plus/es/components/input/style/css',
      'element-plus/es/components/input-number/style/css',
      'element-plus/es/components/option/style/css',
      'element-plus/es/components/pagination/style/css',
      'element-plus/es/components/row/style/css',
      'element-plus/es/components/select/style/css',
      'element-plus/es/components/skeleton/style/css',
      'element-plus/es/components/switch/style/css',
      'element-plus/es/components/table/style/css',
      'element-plus/es/components/table-column/style/css',
      'element-plus/es/components/tag/style/css',
      'element-plus/es/components/message/style/css',
      'element-plus/es/components/tooltip/style/css',
      'element-plus/es/components/popper/style/css',
      'element-plus/es/components/overlay/style/css',
      '@element-plus/icons-vue'
    ]
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': {
        // 端口须与 .env 的 SERVER_PORT 保持一致
        target: 'http://localhost:5661',
        changeOrigin: true
      }
    }
  }
})
