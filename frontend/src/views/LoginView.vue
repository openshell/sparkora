<template>
  <div class="login-page">
    <!-- 左侧品牌区(桌面) -->
    <aside class="login-brand">
      <div class="brand-mark serif">Sparkora<span>☆</span></div>
      <p class="brand-line serif">从主题到发布，<br />一次灵感的完整旅程。</p>
      <p class="brand-sub">AI 辅助的内容创作工作台 —— 简报 · 多版本正文 · 编辑 · 发布</p>
    </aside>

    <!-- 右侧表单区 -->
    <div class="login-panel">
      <div class="login-card">
        <div class="brand-mark compact serif">Sparkora<span>☆</span></div>
        <p class="welcome">欢迎回来，继续你的创作。</p>
        <el-form :model="form" :rules="rules" ref="formRef" label-position="top" @submit.prevent="onSubmit">
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" placeholder="输入用户名" autocomplete="username" />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" type="password" show-password
                      placeholder="输入密码" autocomplete="current-password" />
          </el-form-item>
          <el-button type="primary" :loading="loading" @click="onSubmit" class="submit-btn">登 录</el-button>
        </el-form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '../store/user'
import { ElMessage } from 'element-plus'

const router = useRouter()
const route = useRoute()
const store = useUserStore()
const formRef = ref()
const loading = ref(false)
const form = reactive({ username: '', password: '' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const onSubmit = async () => {
  await formRef.value.validate()
  loading.value = true
  try {
    await store.login(form.username, form.password)
    ElMessage.success('登录成功')
    // 回跳来源页(401 拦截器与路由守卫都会带 redirect)
    router.push(route.query.redirect || '/')
  } catch (e) {
    // 登录失败(R.fail 走 code 检查)或网络异常,均需给出提示
    ElMessage.error(e?.message || '登录失败，请稍后重试')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  background: var(--paper);
}

/* 品牌区:渐变纸面 + 大字刊头,桌面显示 */
.login-brand {
  flex: 1 1 55%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 48px min(8vw, 96px);
  background: var(--brand-gradient);
  color: #fff;
}
.brand-mark { font-weight: 800; font-size: 40px; letter-spacing: .02em; }
.brand-mark span { opacity: .85; }
.brand-line { margin: 18px 0 0; font-size: 30px; font-weight: 700; line-height: 1.45; }
.brand-sub { margin: 14px 0 0; font-size: 14px; opacity: .78; letter-spacing: .05em; }

/* 表单区 */
.login-panel {
  flex: 1 1 45%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
}
.login-card {
  width: 100%;
  max-width: 380px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 36px 30px;
  box-shadow: var(--shadow-card);
}
.brand-mark.compact { font-size: 26px; text-align: center; color: var(--ink); }
.brand-mark.compact span { color: var(--brand); }
.welcome { text-align: center; color: var(--muted); font-size: 13px; margin: 8px 0 24px; }
.submit-btn { width: 100%; margin-top: 4px; }

@media (max-width: 900px) {
  .login-page { flex-direction: column; }
  .login-brand { display: none; } /* 移动端只留表单,避免挤压 */
  .login-card { padding: 28px 22px; }
  .submit-btn { min-height: 44px; }
}
</style>