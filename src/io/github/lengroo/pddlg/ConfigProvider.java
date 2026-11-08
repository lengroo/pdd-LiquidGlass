package io.github.lengroo.pddlg;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Serves the three tunable values to the module code running inside PDD.
 *
 * <p>The tuning UI and the hooked PDD process run under different UIDs,
 * so neither can read the other's SharedPreferences file directly. The
 * UI writes the values into the module app's own prefs; this provider
 * (exported, read-only) hands them to PDD through a ContentResolver
 * query. The values are plain dp numbers, not secrets, so exporting
 * the provider is safe. Writes go through the UI only — insert, delete
 * and update are no-ops.
 */
public class ConfigProvider extends ContentProvider {

    private static final String PREFS = "pdd_liquid_glass_cfg";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        Context ctx = getContext();
        if (ctx == null) {
            return null;
        }
        SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
        MatrixCursor c = new MatrixCursor(
                new String[]{"barOffsetDp", "pillExtraHeightDp", "pillSideInsetDp"});
        c.addRow(new Object[]{
                p.getInt("barOffsetDp", GlassConfig.barOffsetDp),
                p.getInt("pillExtraHeightDp", GlassConfig.pillExtraHeightDp),
                p.getInt("pillSideInsetDp", GlassConfig.pillSideInsetDp)});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.io.github.lengroo.pddlg.config";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null; // read-only; the tuning UI writes the prefs directly
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0; // read-only
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0; // read-only
    }
}
