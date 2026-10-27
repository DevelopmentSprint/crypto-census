<template>
  <div class="evidence-illustration" aria-label="A Java application produces observed cryptographic evidence for a CBOM">
    <div class="illustration-heading"><span class="live-mark" /> FROM EXECUTION TO EVIDENCE <span class="illustration-index">01—03</span></div>
    <div class="trace-stage"><span class="stage-number">01</span><div><strong>Your Java application</strong><small>Tests · staging · representative workloads</small></div><span class="stage-tag">JVM</span></div>
    <div class="trace-connector"><span /> JCA / JSSE calls</div>
    <div class="trace-stage observer"><span class="stage-number">02</span><div><strong>Crypto Census agent</strong><small>Observe → attribute → assess</small></div><svg viewBox="0 0 24 24" width="27" height="27" aria-hidden="true"><circle cx="12" cy="12" r="8" fill="none" stroke="currentColor"/><circle cx="12" cy="12" r="3" fill="currentColor"/><path d="M12 0v5m0 14v5M0 12h5m14 0h5" stroke="currentColor"/></svg></div>
    <div class="trace-connector"><span /> Graceful JVM shutdown</div>
    <div class="evidence-output"><div class="output-heading"><span class="stage-number">03</span><strong>Cryptographic evidence</strong><span class="stage-tag">CBOM</span></div><div class="evidence-row"><span>Algorithm</span><code>AES-GCM</code></div><div class="evidence-row"><span>Application call site</span><code>CardVault.seal:31</code></div><div class="evidence-row"><span>Evidence format</span><code>CycloneDX 1.6</code></div></div>
    <p class="illustration-footnote">Illustrative evidence · Your workload determines what is observed.</p>
  </div>
</template>
