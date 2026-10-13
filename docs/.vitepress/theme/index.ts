import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import { h } from 'vue'
import MermaidDiagram from './components/MermaidDiagram.vue'
import EvidenceIllustration from './components/EvidenceIllustration.vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  Layout: () => h(DefaultTheme.Layout, null, {
    'home-hero-image': () => h(EvidenceIllustration),
    'home-hero-info-before': () => h('p', { class: 'hero-eyebrow' }, 'JAVA CRYPTOGRAPHY · OBSERVED AT RUNTIME'),
  }),
  enhanceApp({ app }) { app.component('MermaidDiagram', MermaidDiagram) },
} satisfies Theme
