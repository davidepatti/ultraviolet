package tests;

import misc.UVConfig;
import stats.JournalCaptureExporter;

/** Resolve settings without starting a simulator. */
public final class S50Config {
    public static void main(String[] args) {
        System.out.println("S50_CONFIG=" + JournalCaptureExporter.canonical(new UVConfig(args[0]).getEffectiveSettings()));
    }
}
