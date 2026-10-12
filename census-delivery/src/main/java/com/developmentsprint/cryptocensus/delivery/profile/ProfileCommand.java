package com.developmentsprint.cryptocensus.delivery.profile;

import com.developmentsprint.cryptocensus.delivery.dependencytrack.DependencyTrackAdapter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** One invocation contract used without semantic differences by Maven and the standalone CLI. */
public final class ProfileCommand {
  private ProfileCommand() {}

  /** Result preserves plan identity and independent protocol observations. */
  public record Result(String planSha256, ProfileReceipt receipt, boolean successful) {}

  /** Executes plan, preflight or publish; planning does not invoke the credential resolver. */
  public static Result run(
      String action, Path profile, String expectedPlanSha256, SecretReference.Resolver resolver)
      throws IOException {
    if (!List.of("plan", "preflight", "publish").contains(action)) {
      throw new IllegalArgumentException("Delivery action must be plan, preflight or publish");
    }
    ProfilePlanner planner =
        new ProfilePlanner(
            new ProfileReader(List.of(new GenericProfileAdapter(), new DependencyTrackAdapter())));
    if ("plan".equals(action)) {
      if (expectedPlanSha256 != null) {
        throw new IllegalArgumentException("Plan action cannot accept an existing plan hash");
      }
      PlannedDelivery plan = planner.plan(profile);
      return new Result(plan.planSha256(), null, true);
    }
    PlannedDelivery plan = planner.load(profile, expectedPlanSha256);
    ProfileExecutor executor = new ProfileExecutor(planner);
    ProfileReceipt receipt =
        "preflight".equals(action)
            ? executor.preflight(plan, resolver)
            : executor.execute(plan, resolver);
    return new Result(
        plan.planSha256(),
        receipt,
        receipt.successful()
            || ("preflight".equals(action)
                && "REMOTE_PREFLIGHT_COMPLETE".equals(receipt.observation().reasonCode())));
  }
}
