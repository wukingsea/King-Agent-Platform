<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import css from 'highlight.js/lib/languages/css'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import markdown from 'highlight.js/lib/languages/markdown'
import plaintext from 'highlight.js/lib/languages/plaintext'
import python from 'highlight.js/lib/languages/python'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'

const languages = {
  bash,
  css,
  java,
  javascript,
  json,
  markdown,
  plaintext,
  python,
  sql,
  typescript,
  xml,
  yaml,
}

Object.entries(languages).forEach(([name, definition]) => {
  hljs.registerLanguage(name, definition)
})

const props = defineProps<{
  content?: string
}>()

const root = ref<HTMLElement | null>(null)

marked.setOptions({
  gfm: true,
  breaks: true,
})

const html = computed(() => {
  const raw = marked.parse(props.content || '', { async: false }) as string
  return DOMPurify.sanitize(raw, {
    ADD_ATTR: ['target', 'rel'],
  })
})

async function decorate() {
  await nextTick()
  const element = root.value
  if (!element) return
  element.querySelectorAll<HTMLAnchorElement>('a').forEach((anchor) => {
    anchor.target = '_blank'
    anchor.rel = 'noopener noreferrer'
  })
  element.querySelectorAll<HTMLElement>('pre code').forEach((block) => {
    if (block.dataset.highlighted === 'yes') return
    try {
      hljs.highlightElement(block)
    } catch {
      /* ignore invalid code */
    }
  })
}

watch(
  () => props.content,
  () => decorate(),
)

onMounted(decorate)
</script>

<template>
  <div ref="root" class="markdown-body" v-html="html"></div>
</template>

<style scoped>
.markdown-body {
  font-size: 14px;
  line-height: 1.78;
  color: var(--king-text-soft);
  word-break: break-word;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 1.25em 0 0.55em;
  color: var(--king-text);
  font-weight: 650;
  line-height: 1.35;
}

.markdown-body :deep(h1) {
  font-size: 1.45em;
}

.markdown-body :deep(h2) {
  font-size: 1.24em;
  padding-bottom: 0.3em;
  border-bottom: 1px solid var(--king-line);
}

.markdown-body :deep(h3) {
  font-size: 1.08em;
}

.markdown-body :deep(p) {
  margin: 0.7em 0;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 0.65em 0;
  padding-left: 1.4em;
}

.markdown-body :deep(li) {
  margin: 0.3em 0;
}

.markdown-body :deep(strong) {
  color: var(--king-text);
  font-weight: 620;
}

.markdown-body :deep(a) {
  color: var(--king-cyan);
  text-decoration: none;
  border-bottom: 1px solid rgba(34, 211, 238, 0.25);
}

.markdown-body :deep(code) {
  padding: 0.16em 0.42em;
  border-radius: 6px;
  background: rgba(124, 92, 255, 0.13);
  border: 1px solid rgba(124, 92, 255, 0.16);
  color: #d9d2ff;
  font-family: var(--king-mono);
  font-size: 0.88em;
}

.markdown-body :deep(pre) {
  position: relative;
  margin: 0.9em 0;
  padding: 16px 18px;
  border-radius: 12px;
  background: #0a0f1f;
  border: 1px solid var(--king-line);
  overflow-x: auto;
}

.markdown-body :deep(pre code) {
  padding: 0;
  border: none;
  background: transparent;
  color: #d5def7;
  font-size: 12.5px;
  line-height: 1.65;
}

.markdown-body :deep(blockquote) {
  margin: 0.9em 0;
  padding: 10px 16px;
  border-left: 3px solid rgba(124, 92, 255, 0.6);
  background: rgba(124, 92, 255, 0.06);
  border-radius: 0 10px 10px 0;
  color: var(--king-text-soft);
}

.markdown-body :deep(table) {
  width: 100%;
  margin: 1em 0;
  border-collapse: collapse;
  font-size: 13px;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  padding: 9px 12px;
  border: 1px solid var(--king-line);
  text-align: left;
}

.markdown-body :deep(th) {
  background: rgba(255, 255, 255, 0.04);
  color: var(--king-text);
  font-weight: 550;
}

.markdown-body :deep(hr) {
  border: none;
  height: 1px;
  background: var(--king-line);
  margin: 1.4em 0;
}

.markdown-body :deep(img) {
  max-width: 100%;
  border-radius: 10px;
}

.markdown-body :deep(> :first-child) {
  margin-top: 0;
}

.markdown-body :deep(> :last-child) {
  margin-bottom: 0;
}
</style>
