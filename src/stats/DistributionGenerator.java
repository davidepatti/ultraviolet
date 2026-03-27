package stats;

import java.util.Arrays;
import java.util.Random;

public class DistributionGenerator {

    private static final double EPSILON = 1e-9;

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

        double minMean = feasibleMeanMin(size, lowerLimit, median);
        double maxMean = feasibleMeanMax(size, median, upperLimit);
        if (mean < minMean - EPSILON || mean > maxMean + EPSILON) {
            throw new IllegalArgumentException(
                    String.format(
                            "Mean %.4f is incompatible with lower=%.4f, median=%.4f, upper=%.4f. Feasible range is [%.4f, %.4f]",
                            mean, lowerLimit, median, upperLimit, minMean, maxMean
                    )
            );
        }
    }

    private static double feasibleMeanMin(int size, double lowerLimit, double median) {
        int lowerCount = size / 2;
        int medianCount = size % 2;
        int upperCount = size / 2;
        return (lowerCount * lowerLimit + (medianCount + upperCount) * median) / size;
    }

    private static double feasibleMeanMax(int size, double median, double upperLimit) {
        int lowerCount = size / 2;
        int medianCount = size % 2;
        int upperCount = size / 2;
        return ((lowerCount + medianCount) * median + upperCount * upperLimit) / size;
    }

    private static double interpolate(double start, double end, double fraction) {
        return start + (end - start) * fraction;
    }

    private static double[] generateMirroredSamples(Random random, int size, double lowerLimit, double upperLimit, double desiredMean) {
        double[] samples = new double[size];
        if (size == 0) {
            return samples;
        }

        double low = Math.max(lowerLimit, 2 * desiredMean - upperLimit);
        double high = Math.min(upperLimit, 2 * desiredMean - lowerLimit);
        int index = 0;

        while (index + 1 < size) {
            double first = high - low < EPSILON ? low : low + (high - low) * random.nextDouble();
            double second = 2 * desiredMean - first;
            samples[index++] = first;
            samples[index++] = second;
        }

        if (index < size) {
            samples[index] = desiredMean;
        }

        shuffle(random, samples);
        return samples;
    }

    private static void shuffle(Random random, double[] values) {
        for (int i = values.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            double tmp = values[i];
            values[i] = values[j];
            values[j] = tmp;
        }
    }

    private static double[] generateDoubleSamplesInternal(Random random, int size, double lowerLimit, double upperLimit, double median, double mean) {
        validateInputs(size, lowerLimit, upperLimit, median, mean);

        int lowerCount = size / 2;
        int medianCount = size % 2;
        int upperCount = size / 2;

        double minMean = feasibleMeanMin(size, lowerLimit, median);
        double maxMean = feasibleMeanMax(size, median, upperLimit);
        double fraction = maxMean - minMean < EPSILON ? 0.0 : (mean - minMean) / (maxMean - minMean);

        double lowerMean = interpolate(lowerLimit, median, fraction);
        double upperMean = interpolate(median, upperLimit, fraction);

        double[] samples = new double[size];
        int offset = 0;

        double[] lowerSamples = generateMirroredSamples(random, lowerCount, lowerLimit, median, lowerMean);
        System.arraycopy(lowerSamples, 0, samples, offset, lowerSamples.length);
        offset += lowerSamples.length;

        if (medianCount == 1) {
            samples[offset++] = median;
        }

        double[] upperSamples = generateMirroredSamples(random, upperCount, median, upperLimit, upperMean);
        System.arraycopy(upperSamples, 0, samples, offset, upperSamples.length);

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
