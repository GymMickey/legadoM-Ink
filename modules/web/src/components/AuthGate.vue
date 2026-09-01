<template>
  <slot v-if="ready" />
  <main v-else-if="authRequired" class="auth-gate">
    <form class="auth-card" @submit.prevent="submit">
      <h1>Web 服务认证</h1>
      <p>请输入阅读应用中显示的 Web 服务 Token。</p>
      <label>
        Token
        <input
          v-model="tokenInput"
          type="password"
          autocomplete="off"
          spellcheck="false"
          required
        />
      </label>
      <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
      <button type="submit" :disabled="submitting">
        {{ submitting ? '验证中…' : '验证并继续' }}
      </button>
    </form>
  </main>
  <main v-else class="auth-gate">
    <p>正在连接阅读应用…</p>
  </main>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { authenticate } from '@/api/api'
import {
  clearWebServiceToken,
  getWebServiceToken,
  setWebServiceToken,
  webServiceAuthRequiredEvent,
} from '@/api/axios'

const ready = ref(false)
const authRequired = ref(false)
const submitting = ref(false)
const tokenInput = ref('')
const errorMessage = ref('')

const isUnauthorized = (error: unknown) =>
  (error as { response?: { status?: number } })?.response?.status === 401

const checkAuthentication = async () => {
  errorMessage.value = ''
  try {
    const response = await authenticate()
    if (!response.data.isSuccess) {
      throw new Error('认证失败')
    }
    authRequired.value = false
    ready.value = true
  } catch (error) {
    ready.value = false
    if (isUnauthorized(error)) {
      clearWebServiceToken()
      authRequired.value = true
    } else {
      errorMessage.value = '无法连接阅读应用，请检查 Web 服务地址。'
    }
  }
}

const submit = async () => {
  const token = tokenInput.value.trim()
  if (!token) return
  submitting.value = true
  errorMessage.value = ''
  setWebServiceToken(token)
  await checkAuthentication()
  if (authRequired.value) {
    tokenInput.value = ''
    errorMessage.value = 'Token 无效或已失效。'
  }
  submitting.value = false
}

const handleAuthRequired = () => {
  clearWebServiceToken()
  ready.value = false
  authRequired.value = true
  tokenInput.value = ''
}

onMounted(() => {
  window.addEventListener(webServiceAuthRequiredEvent, handleAuthRequired)
  tokenInput.value = getWebServiceToken()
  void checkAuthentication()
})

onBeforeUnmount(() => {
  window.removeEventListener(webServiceAuthRequiredEvent, handleAuthRequired)
})
</script>

<style scoped>
.auth-gate {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  box-sizing: border-box;
  font-family: sans-serif;
}

.auth-card {
  width: min(100%, 360px);
  display: grid;
  gap: 14px;
  padding: 24px;
  border: 1px solid #dcdfe6;
  border-radius: 8px;
}

.auth-card h1,
.auth-card p {
  margin: 0;
}

.auth-card label {
  display: grid;
  gap: 6px;
}

.auth-card input,
.auth-card button {
  min-height: 38px;
  box-sizing: border-box;
  padding: 8px 10px;
}

.auth-card button {
  cursor: pointer;
}

.error {
  color: #d03050;
}
</style>
