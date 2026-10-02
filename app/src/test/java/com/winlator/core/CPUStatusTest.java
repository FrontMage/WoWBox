package com.winlator.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CPUStatusTest {
    @Test
    public void calculatesFyReferenceSample() {
        int result = CPUStatus.calculateFrequencyUsagePercent(
                new int[]{
                        2265600, 2265600, 2380800, 2380800,
                        2380800, 2380800, 2380800, 2380800
                },
                new int[]{
                        364800, 364800, 499200, 499200,
                        499200, 499200, 499200, 480000
                },
                new int[]{
                        2265600, 2265600, 3148800, 3148800,
                        3148800, 2956800, 2956800, 3302400
                });

        assertEquals(77, result);
    }

    @Test
    public void calculatesHeterogeneousCoreFrequencyUsage() {
        int result = CPUStatus.calculateFrequencyUsagePercent(
                new int[]{2265600, 2380800, 3302400},
                new int[]{364800, 499200, 480000},
                new int[]{2265600, 3148800, 3302400});

        assertEquals(89, result);
    }

    @Test
    public void skipsInvalidCores() {
        int result = CPUStatus.calculateFrequencyUsagePercent(
                new int[]{1500, 0, 900},
                new int[]{500, 0, 1000},
                new int[]{2500, 0, 1000});

        assertEquals(50, result);
    }

    @Test
    public void clampsOutOfRangeFrequencies() {
        assertEquals(0, CPUStatus.calculateFrequencyUsagePercent(
                new int[]{100}, new int[]{500}, new int[]{2500}));
        assertEquals(100, CPUStatus.calculateFrequencyUsagePercent(
                new int[]{3000}, new int[]{500}, new int[]{2500}));
    }

    @Test
    public void reportsUnavailableWhenNoCoreIsValid() {
        assertEquals(-1, CPUStatus.calculateFrequencyUsagePercent(
                new int[]{0, 1000}, new int[]{0, 1000}, new int[]{0, 1000}));
        assertEquals(-1, CPUStatus.calculateFrequencyUsagePercent(null, null, null));
    }
}
