import java.util.Arrays;
import java.util.Random;

public class DistributionGenerator {

    private static double[] generateNormalSamples(int n, double lowerLimit, double upperLimit) {
        Random random = new Random();
        return random.doubles(n, lowerLimit, upperLimit).toArray(); // Generate N samples within the specified range
    }

    private static double[] skewSamples(double[] samples, double mean, double median, double lowerLimit, double upperLimit) {
        // Sort the samples to apply transformations based on order
        Arrays.sort(samples);
        double medianSample = samples[samples.length / 2]; // Approximate current median of samples

        // Apply skewness by adjusting values based on their relation to the median
        for (int i = 0; i < samples.length; i++) {
            if (samples[i] < medianSample) {
                // Adjust values below the median to be closer or further away, based on required skew
                samples[i] -= (median - mean) / 10; // Adjust divisor for more/less skew
            } else {
                // Adjust values above the median similarly
                samples[i] += (mean - median) / 10; // Adjust divisor for more/less skew
            }
        }

        // Normalize to adjust the actual mean and median to the target values, ensuring they stay within limits
        double currentMean = Arrays.stream(samples).average().orElse(Double.NaN);
        double adjustmentFactor = mean - currentMean;
        for (int i = 0; i < samples.length; i++) {
            samples[i] += adjustmentFactor;
            // Ensure the adjusted values are within the specified limits
            samples[i] = Math.min(Math.max(samples[i], lowerLimit), upperLimit);
        }

        return samples;
    }

    public static double[] generateSkewedSamples(double mean, double median, int n, double lowerLimit, double upperLimit) {
        double[] samples = generateNormalSamples(n, lowerLimit, upperLimit);
        samples = skewSamples(samples, mean, median, lowerLimit, upperLimit);
        return samples;
    }
}