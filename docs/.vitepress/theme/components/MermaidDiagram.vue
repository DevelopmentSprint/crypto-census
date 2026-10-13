<script setup lang="ts">
import { onMounted, onUnmounted, ref, useId, watch } from 'vue'
import { useData } from 'vitepress'

const props = defineProps<{ source: string }>()
const { isDark } = useData()
const svg = ref('')
const failed = ref(false)
const mounted = ref(false)
const id = 'census-diagram-' + useId().replace(/[^a-zA-Z0-9_-]/g, '')
let revision = 0

async function render() {
  if (!mounted.value) return
  const current = ++revision
  failed.value = false
  try {
    const { default: mermaid } = await import('mermaid')
    if (current !== revision) return
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      htmlLabels: false,
      theme: isDark.value ? 'dark' : 'base',
      fontFamily: 'ui-sans-serif, system-ui, sans-serif',
      themeVariables: isDark.value ? { primaryColor: '#163d40', mainBkg: '#163d40', nodeBorder: '#4fc2b3', primaryBorderColor: '#4fc2b3', lineColor: '#94acae', clusterBkg: '#122a30', clusterBorder: '#406167' } : {
        primaryColor: '#e8f5f1', primaryTextColor: '#173537', primaryBorderColor: '#147d73',
        lineColor: '#526d72', secondaryColor: '#eff2f7', tertiaryColor: '#fff7e8',
      },
      flowchart: { curve: 'linear' },
    })
    const result = await mermaid.render(`${id}-${current}`, props.source)
    if (current === revision) svg.value = result.svg
  } catch {
    if (current === revision) { failed.value = true; svg.value = '' }
  }
}
onMounted(() => { mounted.value = true; void render() })
onUnmounted(() => { mounted.value = false; revision++ })
watch([() => props.source, isDark], () => void render())
</script>

<template>
  <figure class="census-diagram" :data-diagram-error="failed || undefined">
    <div v-if="svg" class="diagram-canvas" role="img" aria-label="Documentation diagram; text source follows" v-html="svg" />
    <p v-else class="diagram-status" role="status">{{ failed ? 'The diagram could not render. Its text source is available below.' : 'Loading diagram…' }}</p>
    <details class="diagram-source">
      <summary>Read diagram source</summary>
      <pre><code>{{ source }}</code></pre>
    </details>
  </figure>
</template>
