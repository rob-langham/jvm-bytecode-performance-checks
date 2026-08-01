package demo.runtime;

import com.staticallocationchecker.annotations.AllocationsForWarmup;

/** Warms up once: the buffer is sized once and never outgrown. */
public class PricingEngine {

    private double[] levels;

    @AllocationsForWarmup
    public double[] levels() {
        if (levels == null) {
            levels = new double[4];
        }
        return levels;
    }

    public double price(long tick) {
        double[] buffer = levels();
        buffer[(int) (tick & 3)] = tick;
        return buffer[0];
    }
}
