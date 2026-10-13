import DefaultTheme from 'vitepress/theme'
import { withBase, type Theme } from 'vitepress'
import { h } from 'vue'
import MermaidDiagram from './components/MermaidDiagram.vue'
import EvidenceIllustration from './components/EvidenceIllustration.vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  Layout: () => h(DefaultTheme.Layout, null, {
    'layout-top': () => h('aside', { class: 'release-notice', 'aria-label': 'Release availability' }, [
      h('span', 'Version 0.1.0 is not on Maven Central yet.'),
      h('a', { href: withBase('/getting-started/installation.html') }, 'Install from source'),
    ]),
    'home-hero-image': () => h(EvidenceIllustration),
    'home-hero-info-before': () => h('p', { class: 'hero-eyebrow' }, 'JAVA CRYPTOGRAPHY · OBSERVED AT RUNTIME'),
  }),
  enhanceApp({ app }) { app.component('MermaidDiagram', MermaidDiagram) },
} satisfies Theme
