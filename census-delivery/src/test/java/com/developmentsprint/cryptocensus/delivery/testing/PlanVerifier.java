package com.developmentsprint.cryptocensus.delivery.testing;

import com.developmentsprint.cryptocensus.delivery.profile.GenericProfileAdapter;
import com.developmentsprint.cryptocensus.delivery.profile.ProfilePlanner;
import com.developmentsprint.cryptocensus.delivery.profile.ProfileReader;
import java.nio.file.Path;
import java.util.List;

/** Fresh-JVM regression probe for persistent, canonical delivery plan identity. */
public final class PlanVerifier {
  private PlanVerifier() {}

  /** Loads the expected sealed plan without invoking any transport or secret resolver. */
  public static void main(String[] arguments) throws Exception {
    var planner = new ProfilePlanner(new ProfileReader(List.of(new GenericProfileAdapter())));
    System.out.println(planner.load(Path.of(arguments[0]), arguments[1]).planSha256());
  }
}
