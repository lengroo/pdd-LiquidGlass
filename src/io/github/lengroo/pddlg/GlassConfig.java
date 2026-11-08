package io.github.lengroo.pddlg;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

/**
 * The three tunables the bar exposes, read on every home-activity onResume
 * so a change lands without reinstalling the module or force-stopping PDD.
 *
 * <p>Values flow one way: the module app's tuning UI writes them into the
 * module app's own SharedPreferences, and the code running inside PDD
 * reads them back through {@link ConfigProvider}. A direct file read
 * cannot work — the UI and PDD run under different UIDs, so the UI
 * cannot write PDD's prefs file and PDD cannot read the module app's
 * file. The provider is the bridge. If it ever fails to answer, the
 * PDD-side prefs file is the fallback (the pre-bridge behaviour).
 */
final class GlassConfig {

    /** Read through the host app's context, so it lands in PDD's data dir. */
    private static final String PREFS = "pdd_liquid_glass_cfg";

    /** ContentProvider authority served by the module app. */
    static final String PROVIDER_AUTHORITY = "io.github.lengroo.pddlg.config";

    static final Uri CONFIG_URI =
            Uri.parse("content://" + PROVIDER_AUTHORITY + "/config");

    /** Distance between the bottom of the glass pill and the screen edge, dp. */
    static volatile int barOffsetDp = 24;

    /**
     * Extra pill height on top of the tab content height, dp, split evenly
     * above and below the tabs. The pill is a floating surface, not a docked
     * bar, so it needs breathing room around the tabs — and the lift above
     * only reads as floating if the pill is not glued to the bottom edge.
     */
    static volatile int pillExtraHeightDp = 12;

    /**
     * Side inset of the pill, dp, applied to both sides. The host keeps
     * MATCH_PARENT width and is pulled in by this margin on each side, so
     * the whole pill — glass and tabs — narrows and stays centred. 0 is
     * the full-width bar.
     */
    static volatile int pillSideInsetDp = 0;

    private GlassConfig() {
    }

    static void load(Context ctx) {
        try {
            if (loadFromProvider(ctx)) {
                return;
            }
        } catch (Throwable t) {
            LiquidGlassModule.logErr("config provider read failed, falling back", t);
        }
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
            barOffsetDp = p.getInt("barOffsetDp", barOffsetDp);
            pillExtraHeightDp = p.getInt("pillExtraHeightDp", pillExtraHeightDp);
            pillSideInsetDp = p.getInt("pillSideInsetDp", pillSideInsetDp);
        } catch (Throwable t) {
            LiquidGlassModule.logErr("config load failed", t);
        }
    }

    /** Returns true when the provider answered with both values. */
    private static boolean loadFromProvider(Context ctx) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(CONFIG_URI, null, null, null, null);
            if (c == null || !c.moveToFirst()) {
                return false;
            }
            barOffsetDp = c.getInt(c.getColumnIndexOrThrow("barOffsetDp"));
            pillExtraHeightDp = c.getInt(c.getColumnIndexOrThrow("pillExtraHeightDp"));
            pillSideInsetDp = c.getInt(c.getColumnIndexOrThrow("pillSideInsetDp"));
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
