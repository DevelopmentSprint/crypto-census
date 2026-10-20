import { defineConfig } from 'vitepress'
import path from 'node:path'
import { analyticsHead } from '../../scripts/docs/analytics.mjs'

const repository = 'https://github.com/DevelopmentSprint/crypto-census'
const docsRoot = path.resolve('docs')
const base = process.env.DOCS_BASE || '/'
const release = process.env.DOCS_RELEASE_TAG || ''
const sourceRef = process.env.DOCS_RELEASE_SHA || 'develop'
const versions: { tag: string; sha: string; path: string }[] = JSON.parse(process.env.DOCS_VERSIONS || '[]')


export default defineConfig({
  title: 'Crypto Census',
  description: 'Runtime cryptographic evidence for Java. Understand algorithms, quantum exposure, and the code paths that use them.',
  base,
  srcExclude: ['versions/**'],
  // Public build identity is consumed by the shared banner.
  transformPageData(pageData) { pageData.frontmatter.docsRelease = release },
  lang: 'en-US',
  lastUpdated: true,
  cleanUrls: false,
  appearance: true,
  head: [
    ...analyticsHead(process.env.DOCS_GA4_MEASUREMENT_ID),
    ['link', { rel: 'icon', type: 'image/svg+xml', href: `${base}favicon.svg` }],
    ['meta', { name: 'theme-color', content: '#0b766e' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'Crypto Census' }],
    ['meta', { property: 'og:image', content: 'https://cryptocensus.developmentsprint.com/social.svg' }],
  ],
  sitemap: { hostname: `https://cryptocensus.developmentsprint.com${base}` },
  themeConfig: {
    logo: '/favicon.svg',
    siteTitle: 'Crypto Census',
    nav: [
      { text: 'Start here', link: '/getting-started/' },
      { text: 'Architecture', link: '/architecture/overview' },
      { text: 'Reference', link: '/guides/configuration' },
      { text: 'Example CBOM', link: '/examples/example-cbom' },
      { text: release || 'Latest · snapshot', items: [
        { text: 'Latest · snapshot', link: 'https://cryptocensus.developmentsprint.com/' },
        ...versions.map(version => ({ text: version.tag, link: `https://cryptocensus.developmentsprint.com${version.path}` })),
      ] },
    ],
    sidebar: [
      { text: 'Start here', items: [
        { text: 'Your first cryptographic census', link: '/getting-started/' },
        { text: 'Install & verify', link: '/getting-started/installation' },
        { text: 'Why Crypto Census?', link: '/concepts/why-crypto-census' },
        { text: 'What the evidence means', link: '/concepts/cryptographic-evidence' },
        { text: 'Policy & observation coverage', link: '/concepts/policy-and-coverage' },
      ] },
      { text: 'Architecture', collapsed: false, items: [
        { text: 'System overview', link: '/architecture/overview' },
        { text: 'JVM instrumentation', link: '/architecture/instrumentation' },
        { text: 'Evidence pipeline', link: '/architecture/evidence-pipeline' },
        { text: 'Enterprise delivery', link: '/architecture/enterprise-delivery' },
      ] },
      { text: 'Use Census', collapsed: false, items: [
        { text: 'Configuration map', link: '/guides/configuration' },
        { text: 'CI & failed-test evidence', link: '/guides/ci' },
        { text: 'Standalone JVMs & containers', link: '/guides/runtime-deployment' },
        { text: 'Choose a delivery destination', link: '/guides/enterprise-delivery' },
        { text: 'Live example CBOM', link: '/examples/example-cbom' },
        { text: 'Cryptography walkthrough', link: '/examples/crypto-walkthrough' },
        { text: 'Maven example project', link: '/examples/maven-project' },
        { text: 'JaCoCo, Failsafe & more recipes', link: '/RECIPES' },
      ] },
      { text: 'Configuration reference', collapsed: true, items: [
        { text: 'Maven plugin', link: '/MAVEN' },
        { text: 'Agent & operations', link: '/OPERATIONS' },
        { text: 'Risk policy', link: '/POLICY' },
        { text: 'CI finalization', link: '/CI-FINALIZATION' },
        { text: 'SBOM preparation', link: '/SBOM-PREPARATION' },
        { text: 'Delivery profiles', link: '/DELIVERY-PROFILES' },
        { text: 'Generic HTTPS delivery', link: '/DELIVERY' },
        { text: 'Transport & delivery operations', link: '/DELIVERY-OPERATIONS' },
      ] },
      { text: 'Enterprise destinations', collapsed: true, items: [
        { text: 'Dependency-Track', link: '/DEPENDENCY-TRACK' },
        { text: 'Sonatype SBOM Manager', link: '/SONATYPE-SBOM-MANAGER' },
        { text: 'JFrog Artifactory', link: '/ARTIFACTORY' },
        { text: 'Anchore Enterprise', link: '/ANCHORE' },
      ] },
      { text: 'Operate & troubleshoot', collapsed: true, items: [
        { text: 'Troubleshooting collection', link: '/troubleshooting/' },
        { text: 'Troubleshooting delivery', link: '/troubleshooting/delivery' },
        { text: 'Compatibility', link: '/COMPATIBILITY' },
        { text: 'Conformance matrix', link: '/CONFORMANCE' },
        { text: 'Safety & limitations', link: '/SAFETY' },
        { text: 'Report a vulnerability', link: repository + '/security/advisories/new' },
      ] },
      { text: 'Contribute & maintain', collapsed: true, items: [
        { text: 'Contributing', link: '/CONTRIBUTING' },
        { text: 'Quality & coverage', link: '/reference/quality' },
        { text: 'Enterprise validation', link: '/reference/enterprise-validation' },
        { text: 'Maintaining this site', link: '/reference/documentation' },
        { text: 'Development snapshots', link: '/SNAPSHOTS' },
        { text: 'Release runbook', link: '/RELEASING' },
        { text: 'Design & research', link: '/reference/design-records' },
      ] },
    ],
    search: { provider: 'local' },
    outline: { level: [2, 3], label: 'On this page' },
    socialLinks: [{ icon: 'github', link: repository }],
    editLink: { pattern: repository + (release ? `/blob/${sourceRef}/docs/:path` : '/edit/develop/docs/:path'), text: release ? 'View tagged source' : 'Improve this page' },
    lastUpdated: { text: 'Last updated', formatOptions: { dateStyle: 'medium' } },
    footer: {
      message: 'Apache 2.0 · Runtime evidence, with explicit limits.',
      copyright: 'Copyright © 2026 Development Sprint, LLC',
    },
  },
  markdown: {
    config(md) {
      const defaultFence = md.renderer.rules.fence!
      md.renderer.rules.fence = (tokens, index, options, env, self) => {
        if (tokens[index].info.trim() === 'mermaid') {
          const source = md.utils.escapeHtml(JSON.stringify(tokens[index].content))
          return `<MermaidDiagram :source="${source}" />`
        }
        return defaultFence(tokens, index, options, env, self)
      }
      // Existing repository docs stay valid on GitHub. Links outside the docs root
      // resolve to their repository source instead of becoming broken Pages URLs.
      md.core.ruler.after('inline', 'repository-links', state => {
        const source = state.env.path ?? state.env.relativePath
        if (!source) return
        const sourcePath = path.isAbsolute(source) ? source : path.resolve(docsRoot, source)
        for (const block of state.tokens) {
          for (const token of block.children ?? []) {
            if (token.type !== 'link_open') continue
            let href = token.attrGet('href')
            if (release && href?.startsWith(`${repository}/`)) {
              href = href.replace('/blob/develop/', `/blob/${sourceRef}/`).replace('/tree/develop/', `/tree/${sourceRef}/`)
              token.attrSet('href', href)
            }
            if (!href || /^(?:[a-z]+:|#|\/)/i.test(href)) continue
            const [pathname, fragment] = href.split('#', 2)
            const target = path.resolve(path.dirname(sourcePath), decodeURIComponent(pathname))
            const relative = path.relative(docsRoot, target)
            if (relative.startsWith('..' + path.sep)) {
              const repoPath = path.relative(path.dirname(docsRoot), target).split(path.sep).join('/')
              if (!repoPath.startsWith('../')) token.attrSet('href', `${repository}/blob/${sourceRef}/${repoPath}${fragment ? '#' + fragment : ''}`)
            }
          }
        }
      })
    },
  },
})
