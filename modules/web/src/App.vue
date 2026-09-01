<template>
  <AuthGate>
    <router-view />
  </AuthGate>
</template>

<script setup lang="ts">
import AuthGate from './components/AuthGate.vue'
</script>
