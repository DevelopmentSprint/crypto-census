import DefaultTheme from 'vitepress/theme'
import { withBase, useData, type Theme } from 'vitepress'
import { h } from 'vue'
import MermaidDiagram from './components/MermaidDiagram.vue'
import EvidenceIllustration from './components/EvidenceIllustration.vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  Layout: () => {
    const { frontmatter } = useData()
    const release = frontmatter.value.docsRelease
    return h(DefaultTheme.Layout, null, {
      'layout-top': () => h('aside', { class: 'release-notice', 'aria-label': 'Release availability' }, [
        h('span', release ? `Archived ${release} documentation.` : 'Latest development snapshot documentation.'),
        h('a', { href: release ? 'https://cryptocensus.developmentsprint.com/' : withBase('/SNAPSHOTS.html') }, release ? 'Read Latest' : 'Install a public snapshot'),
      ]),
      'home-hero-image': () => h(EvidenceIllustration),
      'home-hero-info-before': () => h('p', { class: 'hero-eyebrow' }, 'JAVA CRYPTOGRAPHY · OBSERVED AT RUNTIME'),
    })
  },
  enhanceApp({ app }) { app.component('MermaidDiagram', MermaidDiagram) },
} satisfies Theme
