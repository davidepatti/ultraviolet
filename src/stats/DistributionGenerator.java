package stats;

import java.util.Arrays;
import java.util.Random;

public class DistributionGenerator {

    private static final double EPSILON = 1e-9;
    private static final double MIN_ALPHA = 0.15;
    private static final double MAX_ALPHA = 20.0;

    private static void validateInputs(int size, double lowerLimit, double upperLimit, double median, double mean) {
        if (size <= 0) {
            throw new IllegalArgumentException("Sample size must be positive");
        }
        if (lowerLimit > upperLimit) {
            throw new IllegalArgumentException("Lower limit cannot exceed upper limit");
        }
        if (median < lowerLimit || median > upperLimit) {
            throw new IllegalArgumentException("Median must lie within the configured bounds");
        }
        if (mean < lowerLimit || mean > upperLimit) {
            throw new IllegalArgumentException("Mean must lie within the configured bounds");
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void shuffle(Random random, double[] values) {
        for (int i = values.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            double tmp = values[i];
            values[i] = values[j];
            values[j] = tmp;
        }
    }

    private static double sampleLowerSide(Random random, double lowerLimit, double median, double alpha) {
        return lowerLimit + (median - lowerLimit) * Math.pow(random.nextDouble(), alpha);
    }

    private static double sampleUpperSide(Random random, double median, double upperLimit, double alpha) {
        return median + (upperLimit - median) * Math.pow(random.nextDouble(), alpha);
    }

    private static double computeAlpha(double lowerLimit, double upperLimit, double median, double mean) {
        double baseMean = (lowerLimit + median) / 2.0;
        double topMean = (median + upperLimit) / 2.0;
        if (mean <= baseMean + EPSILON) {
            return MAX_ALPHA;
        } else if (mean >= topMean - EPSILON) {
            return MIN_ALPHA;
        }

        double rawAlpha = (upperLimit - lowerLimit) / (2.0 * (mean - baseMean)) - 1.0;
        return clamp(rawAlpha, MIN_ALPHA, MAX_ALPHA);
    }

    private static double generateDoubleSampleInternal(Random random, double lowerLimit, double upperLimit, double median, double mean) {
        validateInputs(1, lowerLimit, upperLimit, median, mean);

        double alpha = computeAlpha(lowerLimit, upperLimit, median, mean);
        if (median <= lowerLimit + EPSILON) {
            return sampleUpperSide(random, median, upperLimit, alpha);
        }
        if (median >= upperLimit - EPSILON) {
            return sampleLowerSide(random, lowerLimit, median, alpha);
        }
        return random.nextBoolean()
                ? sampleLowerSide(random, lowerLimit, median, alpha)
                : sampleUpperSide(random, median, upperLimit, alpha);
    }

    private static double[] generateDoubleSamplesInternal(Random random, int size, double lowerLimit, double upperLimit, double median, double mean) {
        validateInputs(size, lowerLimit, upperLimit, median, mean);

        double[] samples = new double[size];
        for (int i = 0; i < size; i++) {
            samples[i] = generateDoubleSampleInternal(random, lowerLimit, upperLimit, median, mean);
        }

        shuffle(random, samples);
        return samples;
    }

    public static double generateDoubleSample(Random random, int lower_limit, int upper_limit, double median, double mean) {
        return generateDoubleSampleInternal(random, lower_limit, upper_limit, median, mean);
    }

    public static double[] generateDoubleSamples(Random random, int size, int lower_limit, int upper_limit, double median, double mean) {
        return generateDoubleSamplesInternal(random, size, lower_limit, upper_limit, median, mean);
    }

    public static int generateIntSample(Random random, int lower_limit, int upper_limit, double median, double mean) {
        return (int) Math.round(generateDoubleSampleInternal(random, lower_limit, upper_limit, median, mean));
    }

    public static int[] generateIntSamples(Random random, int size, int lower_limit, int upper_limit, double median, double mean) {
        double[] samples = generateDoubleSamplesInternal(random, size, lower_limit, upper_limit, median, mean);
        int[] intSamples = new int[samples.length];
        for (int i = 0; i < samples.length; i++) {
            intSamples[i] = (int) Math.round(samples[i]);
        }
        return intSamples;
    }

    public static void main(String[] args) {
        int size = 100;
        Random random = new Random();
        int[] samples = generateIntSamples(random, size, 50, 100, 60, 80);

        Arrays.sort(samples);
        double recalculatedMean = Arrays.stream(samples).average().orElse(Double.NaN);
        double recalculatedMedian1 = samples[size / 2 - 1];
        double recalculatedMedian2 = samples[size / 2];

        System.out.println("Recalculated Median: " + recalculatedMedian1 + " - " + recalculatedMedian2);
        System.out.println("Recalculated Mean: " + recalculatedMean);
        for (var x : samples) System.out.println(x);
    }
}
