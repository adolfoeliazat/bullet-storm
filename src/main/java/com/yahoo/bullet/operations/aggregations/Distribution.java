package com.yahoo.bullet.operations.aggregations;

import com.yahoo.bullet.BulletConfig;
import com.yahoo.bullet.Utilities;
import com.yahoo.bullet.operations.AggregationOperations.DistributionType;
import com.yahoo.bullet.operations.aggregations.sketches.QuantileSketch;
import com.yahoo.bullet.parsing.Aggregation;
import com.yahoo.bullet.parsing.Error;
import com.yahoo.bullet.parsing.Specification;
import com.yahoo.bullet.record.BulletRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.yahoo.bullet.parsing.Error.makeError;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

/**
 * This {@link Strategy} uses {@link QuantileSketch} to find distributions of a numeric field. Based on the size
 * configured for the sketch, the normalized rank error can be determined and tightly bound.
 */
public class Distribution extends SketchingStrategy<QuantileSketch> {
    public static final int DEFAULT_ENTRIES = 1024;

    public static final int DEFAULT_MAX_POINTS = 100;
    public static final int DEFAULT_POINTS = 1;
    public static final int DEFAULT_ROUNDING = 6;

    // Distribution fields
    public static final String TYPE = "type";
    public static final String POINTS = "points";
    public static final String RANGE_START = "start";
    public static final String RANGE_END = "end";
    public static final String RANGE_INCREMENT = "increment";
    public static final String NUMBER_OF_POINTS = "numberOfPoints";

    private final int entries;
    private final int maxPoints;
    private final int rounding;

    private String field;

    // Copy of the aggregation
    private Aggregation aggregation;

    // Validation
    public static final Map<String, DistributionType> SUPPORTED_DISTRIBUTION_TYPES = new HashMap<>();
    static {
        SUPPORTED_DISTRIBUTION_TYPES.put(DistributionType.QUANTILE.getName(), DistributionType.QUANTILE);
        SUPPORTED_DISTRIBUTION_TYPES.put(DistributionType.PMF.getName(), DistributionType.PMF);
        SUPPORTED_DISTRIBUTION_TYPES.put(DistributionType.CDF.getName(), DistributionType.CDF);
    }
    public static final Error REQUIRES_TYPE_ERROR =
            makeError("The DISTRIBUTION type requires specifying a type", "Please set type to one of: " +
                      SUPPORTED_DISTRIBUTION_TYPES.keySet().stream().collect(Collectors.joining(", ")));
    public static final Error REQUIRES_POINTS_ERROR =
            makeError("The DISTRIBUTION type requires at least one point specified in attributes",
                      "Please add a list of numeric points with points, OR " +
                      "specify a number of equidistant points to generate with numberOfPoints OR " +
                      "specify a range to generate points for with start, end and increment (start < end, increment > 0)");
    public static final Error REQUIRES_POINTS_PROPER_RANGE =
            makeError(DistributionType.QUANTILE.getName() + " requires points in the proper range",
                      "Please add or generate points: 0 <= point <= 1");
    public static final Error REQUIRES_ONE_FIELD_ERROR =
            makeError("The aggregation type requires exactly one field", "Please add exactly one field to fields");

    /**
     * Constructor that requires an {@link Aggregation}.
     *
     * @param aggregation An {@link Aggregation} with valid fields and attributes for this aggregation type.
     */
    @SuppressWarnings("unchecked")
    public Distribution(Aggregation aggregation) {
        super(aggregation);
        entries = ((Number) config.getOrDefault(BulletConfig.DISTRIBUTION_AGGREGATION_SKETCH_ENTRIES,
                                                DEFAULT_ENTRIES)).intValue();
        rounding = ((Number) config.getOrDefault(BulletConfig.DISTRIBUTION_AGGREGATION_GENERATED_POINTS_ROUNDING,
                                                 DEFAULT_ROUNDING)).intValue();
        int pointLimit = ((Number) config.getOrDefault(BulletConfig.DISTRIBUTION_AGGREGATION_MAX_POINTS,
                                                       DEFAULT_MAX_POINTS)).intValue();
        // The max gets rid of negative sizes if accidentally configured.
        maxPoints = Math.max(DEFAULT_POINTS, Math.min(pointLimit, aggregation.getSize()));
        this.aggregation = aggregation;

        // The sketch is initialized in initialize!
    }

    @Override
    public List<Error> initialize() {
        if (Utilities.isEmpty(fields) || fields.size() != 1) {
            return singletonList(REQUIRES_ONE_FIELD_ERROR);
        }

        Map<String, Object> attributes = aggregation.getAttributes();
        if (Utilities.isEmpty(attributes)) {
            return singletonList(REQUIRES_TYPE_ERROR);
        }

        String typeString = Utilities.getCasted(attributes, TYPE, String.class);
        DistributionType type = SUPPORTED_DISTRIBUTION_TYPES.get(typeString);
        if (type == null) {
            return singletonList(REQUIRES_TYPE_ERROR);
        }

        // Try to initialize sketch now
        sketch = getSketch(entries, maxPoints, rounding, type, attributes);

        if (sketch == null) {
            return type == DistributionType.QUANTILE ? asList(REQUIRES_POINTS_ERROR, REQUIRES_POINTS_PROPER_RANGE) :
                                                       singletonList(REQUIRES_POINTS_ERROR);
        }

        // Initialize field since we have exactly 1
        field = fields.get(0);

        return null;
    }

    @Override
    public void consume(BulletRecord data) {
        Number value = Specification.extractFieldAsNumber(field, data);
        if (value != null) {
            sketch.update(value.doubleValue());
        }
    }

    private static QuantileSketch getSketch(int entries, int maxPoints, int rounding,
                                            DistributionType type, Map<String, Object> attributes) {
        int equidistantPoints = getNumberOfEquidistantPoints(attributes);
        if (equidistantPoints > 0) {
            return new QuantileSketch(entries, rounding, type, Math.min(equidistantPoints, maxPoints));
        }
        List<Double> points = getProvidedPoints(attributes);
        if (Utilities.isEmpty(points)) {
            points = generatePoints(maxPoints, rounding, attributes);
        }

        // If still not good, return null
        if (Utilities.isEmpty(points)) {
            return null;
        }
        // Sort and get first maxPoints distinct values
        double[] cleanedPoints = points.stream().distinct().sorted().limit(maxPoints)
                                       .mapToDouble(d -> d).toArray();

        if (invalidBounds(type, cleanedPoints)) {
            return null;
        }

        return new QuantileSketch(entries, type, cleanedPoints);
    }

    private static boolean invalidBounds(DistributionType type, double[] points) {
        // No points or if type is QUANTILE, invalid range if the start < 0 or end > 1
        return points.length < 1 || (type == DistributionType.QUANTILE && (points[0] < 0.0 ||
                                                                           points[points.length - 1] > 1.0));
    }

    // Point generation methods

    @SuppressWarnings("unchecked")
    private static List<Double> getProvidedPoints(Map<String, Object> attributes) {
        List<Double> points = Utilities.getCasted(attributes, POINTS, List.class);
        if (!Utilities.isEmpty(points)) {
            return points;
        }
        return Collections.emptyList();
    }

    private static List<Double> generatePoints(int maxPoints, int rounding, Map<String, Object> attributes) {
        Number start = Utilities.getCasted(attributes, RANGE_START, Number.class);
        Number end = Utilities.getCasted(attributes, RANGE_END, Number.class);
        Number increment = Utilities.getCasted(attributes, RANGE_INCREMENT, Number.class);

        if (!areNumbersValid(start, end, increment)) {
            return Collections.emptyList();
        }
        Double from = start.doubleValue();
        Double to = end.doubleValue();
        Double by = increment.doubleValue();
        List<Double> points = new ArrayList<>();
        for (int i = 0; i < maxPoints && from <= to; ++i) {
            points.add(Utilities.round(from, rounding));
            from += by;
        }
        return points;
    }

    private static int getNumberOfEquidistantPoints(Map<String, Object> attributes) {
        Number equidistantPoints = Utilities.getCasted(attributes, NUMBER_OF_POINTS, Number.class);
        if (equidistantPoints == null || equidistantPoints.intValue() < 0) {
            return 0;
        }
        return equidistantPoints.intValue();
    }

    private static boolean areNumbersValid(Number start, Number end, Number increment) {
        if (start == null || end == null || increment == null) {
            return false;
        }
        return start.doubleValue() < end.doubleValue() && increment.doubleValue() > 0;
    }
}
