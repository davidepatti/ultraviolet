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

    private static double[] generateDoubleSamplesInternal(Random random, int size, double lowerLimit, double upperLimit, double median, double mean) {
        validateInputs(size, lowerLimit, upperLimit, median, mean);

        double baseMean = (lowerLimit + median) / 2.0;
        double topMean = (median + upperLimit) / 2.0;
        double alpha;
        if (mean <= baseMean + EPSILON) {
            alpha = MAX_ALPHA;
        } else if (mean >= topMean - EPSILON) {
            alpha = MIN_ALPHA;
        } else {
            double rawAlpha = (upperLimit - lowerLimit) / (2.0 * (mean - baseMean)) - 1.0;
            alpha = clamp(rawAlpha, MIN_ALPHA, MAX_ALPHA);
        }

        int lowerCount = size / 2;
        int medianCount = size % 2;
        int upperCount = size / 2;

        double[] samples = new double[size];
        int offset = 0;
        for (int i = 0; i < lowerCount; i++) {
            samples[offset++] = sampleLowerSide(random, lowerLimit, median, alpha);
        }
        if (medianCount == 1) {
            samples[offset++] = median;
        }
        for (int i = 0; i < upperCount; i++) {
            samples[offset++] = sampleUpperSide(random, median, upperLimit, alpha);
        }

        shuffle(random, samples);
        return samples;
    }

    public static double[] generateDoubleSamples(Random random, int size, int lower_limit, int upper_limit, double median, double mean) {
        return generateDoubleSamplesInternal(random, size, lower_limit, upper_limit, median, mean);
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
