package com.winlator.core;

public abstract class CPUStatus {
    private static String getCpuFreqPath(int cpuIndex, String fileName) {
        return "/sys/devices/system/cpu/cpu" + cpuIndex + "/cpufreq/" + fileName;
    }

    public static short[] getCurrentClockSpeeds() {
        int numProcessors = Runtime.getRuntime().availableProcessors();
        short[] clockSpeeds = new short[numProcessors];
        for (int i = 0; i < numProcessors; i++) {
            int currFreq = FileUtils.readInt(getCpuFreqPath(i, "scaling_cur_freq"));
            clockSpeeds[i] = (short)(currFreq / 1000);
        }
        return clockSpeeds;
    }

    public static short getMaxClockSpeed(int cpuIndex) {
        int maxFreq = FileUtils.readInt(getCpuFreqPath(cpuIndex, "cpuinfo_max_freq"));
        return (short)(maxFreq / 1000);
    }

    /**
     * Frequency-based CPU load proxy used when Android denies application
     * access to the aggregate scheduler counters in /proc/stat.
     */
    public static int readFrequencyUsagePercent() {
        int coreCount = Math.max(1, Runtime.getRuntime().availableProcessors());
        int[] currentFreqs = new int[coreCount];
        int[] minFreqs = new int[coreCount];
        int[] maxFreqs = new int[coreCount];
        for (int i = 0; i < coreCount; i++) {
            currentFreqs[i] = FileUtils.readInt(getCpuFreqPath(i, "scaling_cur_freq"));
            minFreqs[i] = FileUtils.readInt(getCpuFreqPath(i, "cpuinfo_min_freq"));
            maxFreqs[i] = FileUtils.readInt(getCpuFreqPath(i, "cpuinfo_max_freq"));
        }
        return calculateFrequencyUsagePercent(currentFreqs, minFreqs, maxFreqs);
    }

    static int calculateFrequencyUsagePercent(
            int[] currentFreqs, int[] minFreqs, int[] maxFreqs) {
        if (currentFreqs == null || minFreqs == null || maxFreqs == null) return -1;
        int coreCount = Math.min(currentFreqs.length, Math.min(minFreqs.length, maxFreqs.length));
        long currentTotal = 0L;
        long minTotal = 0L;
        long maxTotal = 0L;
        int validCoreCount = 0;
        for (int i = 0; i < coreCount; i++) {
            int min = minFreqs[i];
            int max = maxFreqs[i];
            int current = currentFreqs[i];
            if (current <= 0 || min < 0 || max <= min) continue;
            currentTotal += Math.max(min, Math.min(max, current));
            minTotal += min;
            maxTotal += max;
            validCoreCount++;
        }
        if (validCoreCount == 0 || maxTotal <= minTotal) return -1;
        long usage = ((currentTotal - minTotal) * 100L) / (maxTotal - minTotal);
        return (int)Math.max(0L, Math.min(100L, usage));
    }
}
