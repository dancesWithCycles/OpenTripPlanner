package org.opentripplanner.updater.trip.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.opentripplanner.model.UpdateError;
import org.opentripplanner.model.UpdateSuccess;
import org.opentripplanner.updater.UpdateResult;
import org.opentripplanner.updater.trip.UrlUpdaterParameters;

/**
 * Records micrometer metrics for trip updaters that stream trip updates into the system, for
 * example GTFS-RT via MQTT.
 * <p>
 * It records the trip update as counters (continuously increasing numbers) since the concept of
 * "latest update" doesn't exist for them.
 * <p>
 * Use your metrics database to convert the counters to rates.
 */
public class StreamingTripUpdateMetrics extends TripUpdateMetrics {

  protected static final String METRICS_PREFIX = "streaming_trip_updates";
  private final Counter successfulCounter;
  private final Counter failureCounter;
  private final Counter warningsCounter;
  private final Map<UpdateError.UpdateErrorType, Counter> failuresByType = new HashMap<>();
  private final Map<UpdateSuccess.WarningType, Counter> warningsByType = new HashMap<>();

  public StreamingTripUpdateMetrics(UrlUpdaterParameters parameters) {
    super(parameters);
    this.successfulCounter = getCounter("successful", "Total successfully applied trip updates");
    this.failureCounter = getCounter("failed", "Total failed trip updates");
    this.warningsCounter = getCounter("warnings", "Total warnings for successful trip updates");
  }

  public void setCounters(UpdateResult result) {
    this.successfulCounter.increment(result.successful());
    this.failureCounter.increment(result.failed());
    this.warningsCounter.increment(result.warnings().size());

    setFailures(result);
    setWarnings(result);
  }

  private void setWarnings(UpdateResult result) {
    for (var warningType : result.warnings()) {
      var counter = warningsByType.get(warningType);
      if (Objects.isNull(counter)) {
        counter =
          getCounter(
            "warning_type",
            "Total warnings by type generated by successful trip updates",
            Tag.of("warningType", warningType.name())
          );
        warningsByType.put(warningType, counter);
      }
      counter.increment();
    }
  }

  private void setFailures(UpdateResult result) {
    for (var errorType : result.failures().keySet()) {
      var counter = failuresByType.get(errorType);
      if (Objects.isNull(counter)) {
        counter =
          getCounter(
            "failure_type",
            "Total failed trip updates by type",
            Tag.of("errorType", errorType.name())
          );
        failuresByType.put(errorType, counter);
      }
      counter.increment(result.failures().get(errorType).size());
    }
  }

  private Counter getCounter(String name, String description, Tag... tags) {
    var finalTags = Tags.concat(Arrays.stream(tags).toList(), baseTags);
    return Counter
      .builder(METRICS_PREFIX + "." + name)
      .description(description)
      .tags(finalTags)
      .register(Metrics.globalRegistry);
  }
}
