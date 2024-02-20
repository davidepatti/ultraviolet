import java.util.Arrays;
import java.util.Random;

public class DistributionGenerator {

    private static Random random = new Random();

    // Generates initial samples within specified bounds and median
    private static double[] generateUniformSamplesWithMedian(int size, double lowerLimit, double upperLimit, double median) {
        double[] samples = new double[size];
        // Fill first half with values below median
        for (int i = 0; i < size / 2; i++) {
            samples[i] = lowerLimit + (median - lowerLimit) * random.nextDouble();
        }
        // Fill second half with values above median
        for (int i = size / 2; i < size; i++) {
            samples[i] = median + (upperLimit - median) * random.nextDouble();
        }
        return samples;
    }

    // Adjusts samples to achieve the desired mean
    private static double[] adjustSamplesMean(double[] samples,  double lowerLimit, double upperLimit, double median, double desiredMean) {
        double current_sum = Arrays.stream(samples).sum();
        double desired_sum = desiredMean*samples.length;
        double adj_sum = desired_sum-current_sum;

        // try to adjust proportianally to median sides sizes
        double lower_fraction = (median-lowerLimit)/(upperLimit-lowerLimit);
        double upper_fraction = (upperLimit-median)/(upperLimit-lowerLimit);

        double adj_sum_lower = lower_fraction*adj_sum;
        double adj_sum_upper = upper_fraction*adj_sum;

        double adj_sample_lower = adj_sum_lower/(samples.length/2.0);
        double adj_sample_upper = adj_sum_upper/(samples.length/2.0);


        // Apply adjustments
        for (int i = 0; i < samples.length; i++) {
            if (samples[i] < median) {
                // Adjust the lower half
                samples[i] += adj_sample_lower;
                // Ensure it doesn't cross the median or lower limit
                if (samples[i] >= median) samples[i] = median - 0.0001;
                if (samples[i] < lowerLimit) samples[i] = lowerLimit;
            } else {
                // Adjust the upper half
                samples[i] += adj_sample_upper;
                // Ensure it doesn't cross the median or exceed the upper limit
                if (samples[i] < median) samples[i] = median;
                if (samples[i] > upperLimit) samples[i] = upperLimit;
            }
        }

        Arrays.sort(samples); // Re-sort samples to ensure order after adjustment
        return samples;
    }

    public static double[] generateDoubleSamples(int size, int lower_limit, int upper_limit, double median, double mean) {

        double[] samples = generateUniformSamplesWithMedian(size, lower_limit, upper_limit, median);
        return adjustSamplesMean(samples,lower_limit,upper_limit, median, mean);
    }
    public static int[] generateIntSamples(int size, int lower_limit, int upper_limit, double median, double mean) {
        double[] samples = generateUniformSamplesWithMedian(size, lower_limit, upper_limit, median);
        samples = adjustSamplesMean(samples,lower_limit,upper_limit, median, mean);
        // Convert double[] to int[]
        int[] intSamples = new int[samples.length];
        for (int i = 0; i < samples.length; i++){
            intSamples[i] = (int)Math.round(samples[i]);  // round off to nearest integer
        }
        return intSamples;
    }

    public static void main(String[] args) {
        int size = 100;
        //double[] samples = generateDoubleSamples(size,0,500,200,100);
        int[] samples = generateIntSamples(size,50,100,60,80);

        // Sorting to check the results
        Arrays.sort(samples);
        double recalculatedMean = Arrays.stream(samples).average().orElse(Double.NaN);
        double recalculatedMedian1 = samples[size / 2-1];
        double recalculatedMedian2 = samples[size / 2];

        System.out.println("Recalculated Median: " + recalculatedMedian1+ " - "+recalculatedMedian2);
        System.out.println("Recalculated Mean: " + recalculatedMean);
        for (var x : samples) System.out.println(x);
        //for (var x : samples2) System.out.println(x);
    }
}
