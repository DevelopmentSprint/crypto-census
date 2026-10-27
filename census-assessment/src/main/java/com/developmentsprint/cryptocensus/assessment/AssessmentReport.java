package com.developmentsprint.cryptocensus.assessment;

import com.developmentsprint.cryptocensus.core.CanonicalJson;
import com.developmentsprint.cryptocensus.core.DocumentFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Immutable canonical assessment artifact; presentation timestamps are outside its content hash.
 */
public final class AssessmentReport {
  public static final int VERSION = 1;
  private final byte[] bytes;

  private AssessmentReport(Map<String, Object> data) {
    bytes = CanonicalJson.encode(data);
  }

  static AssessmentReport of(Map<String, Object> data) {
    return new AssessmentReport(data);
  }

  /** Returns an owned copy of the deterministic report bytes. */
  public byte[] bytes() {
    return bytes.clone();
  }

  /** Content digest changes with inputs, declarations, policy or findings, never render time. */
  public String revision() {
    return CanonicalJson.hash(bytes);
  }

  /** Returns a deeply immutable decoded view for presentation without recomputing policy. */
  public Map<String, Object> data() {
    return CanonicalJson.object(CanonicalJson.decode(bytes));
  }

  /** Publishes the canonical artifact with the shared exclusive-create/atomic-replace writer. */
  public void write(Path path) throws IOException {
    DocumentFiles.write(path, bytes);
  }
}
