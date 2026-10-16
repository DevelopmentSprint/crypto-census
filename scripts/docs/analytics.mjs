/**
 * GA4 is opt-in at build time. Published artifacts remain inert on preview hosts.
 * Page loads and SPA navigation use GA4 enhanced measurement; do not add a
 * second router-based page_view emitter alongside it.
 * @returns {import('vitepress').HeadConfig[]}
 */
export function analyticsHead(measurementId = '') {
  const id = measurementId.trim()
  if (!id) return []
  if (!/^G-[A-Z0-9]+$/.test(id)) {
    throw new Error('DOCS_GA4_MEASUREMENT_ID must be a GA4 measurement ID (G-XXXXXXXXXX)')
  }
  return [['script', { id: 'docs-ga4' }, `
(() => {
  if (location.origin !== 'https://cryptocensus.developmentsprint.com' ||
      navigator.doNotTrack === '1' || window.gtag) return;
  window.dataLayer = window.dataLayer || [];
  window.gtag = function () { window.dataLayer.push(arguments); };
  window.gtag('js', new Date());
  window.gtag('config', ${JSON.stringify(id)}, {
    allow_google_signals: false,
    allow_ad_personalization_signals: false
  });
  const tag = document.createElement('script');
  tag.async = true;
  tag.src = 'https://www.googletagmanager.com/gtag/js?id=${id}';
  document.head.appendChild(tag);
})();`]]
}
