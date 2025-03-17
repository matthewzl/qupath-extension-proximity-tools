package qupath.ext.proximity;

import org.apache.commons.math3.optim.nonlinear.scalar.noderiv.SimplexOptimizer;
import org.apache.commons.math3.optim.nonlinear.scalar.noderiv.NelderMeadSimplex;
import org.apache.commons.math3.optim.nonlinear.scalar.ObjectiveFunction;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;
import org.apache.commons.math3.optim.InitialGuess;
import org.apache.commons.math3.optim.MaxEval;
import org.apache.commons.math3.optim.PointValuePair;
import org.apache.commons.math3.analysis.MultivariateFunction;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

import java.util.List;

public class PTMath {

    /**
     * Pass in a list of doubles to get a DescriptiveStatistics object of it.
     *
     * @param data a List of doubles (distances, etc.)
     * @return the DescriptiveStatistics object
     */
    public static DescriptiveStatistics getDescriptiveStatistics(List<Double> data) {
        DescriptiveStatistics descriptiveStatistics = new DescriptiveStatistics();
        data.forEach(d -> descriptiveStatistics.addValue(d));

        return descriptiveStatistics;
    }

    /**
     * Fits a Weibull distribution to the given non-negative data using
     * maximum likelihood estimation (MLE).
     * This has been tested against SciPy's weibull_min with fairly consistent
     * results.
     *
     * @param data a List of nonnegative doubles (distances, etc.)
     * @return an array [shape, scale], i.e. [k, lambda]
     */
    // TODO: 1) Address numerical stability for small x values. 2) Improve initial parameter guesses.
    public static double[] fitWeibull(List<Double> data) throws IllegalArgumentException {

        final double[] values = data.stream().mapToDouble(Double::doubleValue).toArray();
        if (values.length == 0) {
            throw new IllegalArgumentException("No data provided.");
        }

        MultivariateFunction negLogLikelihood = new MultivariateFunction() {
            @Override
            public double value(double[] point) {
                double k = point[0]; // shape
                double lam = point[1]; // scale


                if (k <= 0 || lam <= 0) { // enforce positivity
                    return Double.POSITIVE_INFINITY;
                }

                double nll = 0.0;
                for (double x : values) {
                    if (x < 0) {
                        return Double.POSITIVE_INFINITY; // invalid data
                    }
                    // handle the case x=0 => log(x/lambda) is problematic
                    // 0 is valid if k>1 => pdf(0)=0. Do a check:
                    if (x == 0) {
                        // logPdf -> limit is log(k) - log(lambda).
                        double logPdfAt0 = Math.log(k) - Math.log(lam);
                        nll -= logPdfAt0;
                    } else {
                        double ratio = x / lam;
                        double logPdf = Math.log(k)
                                - Math.log(lam)
                                + (k - 1.0) * Math.log(ratio)
                                - Math.pow(ratio, k);
                        nll -= logPdf;
                    }
                }
                return nll;
            }
        };

        // optimizer: Nelder–Mead with a basic 2D simplex
        SimplexOptimizer optimizer = new SimplexOptimizer(1e-9, 1e-9);

        // initial guess & simplex step
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        double mean = sum / values.length;
        double initialShape = 1.0;
        double initialScale = (mean == 0.0) ? 1.0 : mean; // fallback if all zero

        // perform optimization with NelderMeadSimplex
        NelderMeadSimplex simplex = new NelderMeadSimplex(new double[]{0.1, 0.1});
        PointValuePair result = optimizer.optimize(
                new MaxEval(10000),
                new ObjectiveFunction(negLogLikelihood),
                GoalType.MINIMIZE,
                new InitialGuess(new double[]{initialShape, initialScale}),
                simplex
        );

        // extract the best-fit parameters
        double[] params = result.getPoint();
        double shape = params[0];
        double scale = params[1];

        return new double[]{shape, scale};
    }

}
