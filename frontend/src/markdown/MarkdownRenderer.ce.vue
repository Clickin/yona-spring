<script setup lang="ts">
// The HTMLElement bridge parses and sanitizes the immutable snapshot before mounting.
defineProps<{markup: string}>();
</script>

<template>
  <div class="markdown-wrap">
    <div class="markdown-output" v-html="markup"></div>
  </div>
</template>

<style>
:host { display: block; }
.markdown-wrap { padding: 0 !important; font-size: inherit; }
.markdown-output img { max-width: 100%; }
</style>
