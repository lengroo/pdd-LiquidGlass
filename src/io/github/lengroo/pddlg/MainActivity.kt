package io.github.lengroo.pddlg

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    }
}
