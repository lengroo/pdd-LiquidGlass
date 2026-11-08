package io.github.lengroo.pddlg

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * The module's launcher entry: an About screen ported from KernelSU's
 * MIUIX About screen (manager AboutMiuix.kt), simplified — no parallax
 * logo, no blur backdrop, just the logo, app name, version, and a link
 * card. Links: GitHub (this repo) and Telegram (the owner's channel).
 * There is intentionally NO QQ channel row.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        try {
            setContent {
                val themeController = remember {
                    ThemeController(ColorSchemeMode.System)
                }
                MiuixTheme(controller = themeController) {
                    Scaffold(
                        topBar = {
                            SmallTopAppBar(
                                title = getString(R.string.about_title),
                            )
                        },
                        content = { paddingValues ->
                            AboutScreen(paddingValues = paddingValues)
                        },
                    )
                }
            }
        } catch (e: Throwable) {
            val view = android.widget.TextView(this)
            view.text = "About failed to start."
            val d = resources.displayMetrics.density
            view.setPadding(
                (24 * d).toInt(), (72 * d).toInt(),
                (24 * d).toInt(), (24 * d).toInt(),
            )
            setContentView(view)
        }
    }
}

@Composable
private fun AboutScreen(paddingValues: PaddingValues) {
    val context = LocalContext.current
    val versionName = remember {
        try {
            @Suppress("DEPRECATION")
            context.packageManager
                .getPackageInfo(context.packageName, 0).versionName
                ?: ""
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
    }
    // Tuning values, read from the module app's own prefs (the same file
    // ConfigProvider serves to PDD). Written on every slider move; PDD
    // picks them up on the next home onResume, no reinstall needed.
    // Note: GlassConfig is Java compiled after this Kotlin file, so the UI
    // reads/writes the prefs directly instead of calling into it.
    var barOffset by remember {
        mutableIntStateOf(readTuningValue(context, "barOffsetDp", 24))
    }
    var pillExtra by remember {
        mutableIntStateOf(readTuningValue(context, "pillExtraHeightDp", 12))
    }
    var pillSide by remember {
        mutableIntStateOf(readTuningValue(context, "pillSideInsetDp", 0))
    }
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(paddingValues)
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Logo (the module's own launcher foreground, red pill +
        // droplet; shown as-is, no tint — it carries its own colors).
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .padding(top = 48.dp)
                .size(100.dp),
        )
        Text(
            text = context.getString(R.string.app_name),
            color = MiuixTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = versionName,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp, bottom = 24.dp),
        )
        // Link card: GitHub + Telegram only. No QQ row.
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(0.dp),
        ) {
            BasicComponent(
                title = context.getString(R.string.about_github),
                summary = context.getString(R.string.about_github_summary),
                endActions = {
                    Icon(
                        imageVector = MiuixIcons.Basic.ArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                },
                onClick = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/lengroo/pdd-LiquidGlass"),
                        ),
                    )
                },
            )
            BasicComponent(
                title = context.getString(R.string.about_telegram),
                summary = context.getString(R.string.about_telegram_summary),
                endActions = {
                    Icon(
                        imageVector = MiuixIcons.Basic.ArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                },
                onClick = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://t.me/fengjimmm"),
                        ),
                    )
                },
            )
        }
        // Tuning card: barOffsetDp + pillExtraHeightDp. Values are written
        // to the module app's prefs on every move; PDD reads them through
        // ConfigProvider on the next home onResume and re-applies the pill
        // in place — no reinstall, no force-stop.
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            insideMargin = PaddingValues(12.dp),
        ) {
            Column {
                Text(
                    text = context.getString(R.string.tuning_title),
                    color = MiuixTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Text(
                    text = context.getString(R.string.tuning_hint),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = context.getString(R.string.tuning_bar_offset),
                        color = MiuixTheme.colorScheme.onBackground,
                        fontSize = 15.sp,
                    )
                    Text(
                        text = "${barOffset}dp",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 15.sp,
                    )
                }
                Slider(
                    value = barOffset.toFloat(),
                    onValueChange = { v ->
                        val n = v.toInt().coerceIn(0, 48)
                        barOffset = n
                        writeTuningValue(context, "barOffsetDp", n)
                    },
                    valueRange = 0f..48f,
                    steps = 47,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = context.getString(R.string.tuning_pill_extra),
                        color = MiuixTheme.colorScheme.onBackground,
                        fontSize = 15.sp,
                    )
                    Text(
                        text = "${pillExtra}dp",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 15.sp,
                    )
                }
                Slider(
                    value = pillExtra.toFloat(),
                    onValueChange = { v ->
                        val n = v.toInt().coerceIn(0, 24)
                        pillExtra = n
                        writeTuningValue(context, "pillExtraHeightDp", n)
                    },
                    valueRange = 0f..24f,
                    steps = 23,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = context.getString(R.string.tuning_pill_side),
                        color = MiuixTheme.colorScheme.onBackground,
                        fontSize = 15.sp,
                    )
                    Text(
                        text = "${pillSide}dp",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 15.sp,
                    )
                }
                Slider(
                    value = pillSide.toFloat(),
                    onValueChange = { v ->
                        val n = v.toInt().coerceIn(0, 48)
                        pillSide = n
                        writeTuningValue(context, "pillSideInsetDp", n)
                    },
                    valueRange = 0f..48f,
                    steps = 47,
                    modifier = Modifier.fillMaxWidth(),
                )
                BasicComponent(
                    title = context.getString(R.string.tuning_reset),
                    onClick = {
                        barOffset = 24
                        pillExtra = 12
                        pillSide = 0
                        writeTuningValue(context, "barOffsetDp", 24)
                        writeTuningValue(context, "pillExtraHeightDp", 12)
                        writeTuningValue(context, "pillSideInsetDp", 0)
                    },
                )
            }
        }
    }
}

/**
 * Reads/writes the tuning values in the module app's own prefs — the same
 * file ConfigProvider serves to PDD. Kept here (not in GlassConfig) because
 * the Kotlin UI compiles before the Java module sources.
 */
private fun readTuningValue(context: Context, key: String, def: Int): Int {
    return try {
        context.getSharedPreferences("pdd_liquid_glass_cfg", 0).getInt(key, def)
    } catch (e: Throwable) {
        def
    }
}

private fun writeTuningValue(context: Context, key: String, value: Int) {
    try {
        context.getSharedPreferences("pdd_liquid_glass_cfg", 0)
            .edit().putInt(key, value).apply()
    } catch (e: Throwable) {
        // Best effort; a failed write just leaves the old value.
    }
}
