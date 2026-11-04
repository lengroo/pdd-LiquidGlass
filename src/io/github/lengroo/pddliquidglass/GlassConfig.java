package io.github.lengroo.pddliquidglass;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The one tunable the bar exposes, read from the host app's own SharedPreferences.
 *
 * <p>Nothing in the module writes this file — there is no settings UI — but it
 * lets the float height be changed without a rebuild, which is the only value
 * that is really a matter of taste. Each host keeps its own copy, since the
 * file is read through that app's context and lands in that app's data dir.
 */
final class GlassConfig {

    /** Read through the host app's context, so it lands in PDD's data dir. */
    private static final String PREFS = "pdd_liquid_glass_cfg";

    /** Distance between the bottom of the glass pill and the screen edge, dp. */
    static volatile int barOffsetDp = 24;

    /**
     * Extra pill height on top of the tab content height, dp, split evenly
     * above and below the tabs. The pill is a floating surface, not a docked
     * bar, so it needs breathing room around the tabs — and the lift above
     * only reads as floating if the pill is not glued to the bottom edge.
     */
    static volatile int pillExtraHeightDp = 12;

    private GlassConfig() {
    }

    static void load(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
            barOffsetDp = p.getInt("barOffsetDp", barOffsetDp);
        } catch (Throwable t) {
            LiquidGlassModule.logErr("config load failed", t);
        }
    }
}
