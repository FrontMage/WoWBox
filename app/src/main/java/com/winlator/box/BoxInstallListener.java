package com.winlator.box;

public interface BoxInstallListener {
    void onProgress(String stage, int overallProgress, int currentProgress, String detail);
}
