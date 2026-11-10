package io.github.lengroo.pddlg

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * The module's launcher entry.
 *
 * <p>Three screens behind one menu, all MIUIX: the main menu lists
 * 关于 / 微调 / 主题, each opening its own screen (the sub-screens get
 * a back button in the top bar). The theme mode is stored in the module
 * app's own prefs and served to PDD through ConfigProvider; it also
 * drives this UI's own theme, so the picker shows what it does.
 */
private enum class Screen {
    Main, About, Tuning, Theme
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        try {
            setContent {
                val context = LocalContext.current
                var screen by remember { mutableStateOf(Screen.Main) }
                var themeMode by remember {
                    mutableIntStateOf(readTuningValue(context, "themeMode", 0))
                }
                val themeController = remember(themeMode) {
                    ThemeController(
                        when (themeMode) {
                            1 -> ColorSchemeMode.Light
                            2 -> ColorSchemeMode.Dark
                            else -> ColorSchemeMode.System
                        }
                    )
                }
                // System back (gesture/button) on a sub-screen returns to
                // the main menu instead of exiting the app.
                BackHandler(enabled = screen != Screen.Main) {
                    screen = Screen.Main
                }
                // MIUIX popups (OverlayDropdownPreference) register a
                // NavigationBackHandler, which needs a NavigationEventDispatcher
                // via LocalNavigationEventDispatcherOwner. This manual build's
                // ComponentActivity (activity 1.11.0) does not provide one, so
                // tapping the dropdown crashed with IllegalStateException
                // ("No NavigationEventDispatcher was provided"). Provide a
                // local dispatcher here.
                //
                // The standalone dispatcher receives no system-back events
                // on its own, so bridge them in: the preference reports its
                // open/closed state via onExpandedChange (dropdownOpen), and
                // a BackHandler composed after the screen BackHandler (so it
                // wins while enabled) forwards one back press into the
                // dispatcher as started -> progressed(1f) -> completed. The
                // popup's NavigationBackHandler then dismisses it, instead
                // of back dropping to the main menu. The progressed step
                // matters: started -> completed alone never fires the
                // popup's onBackCompleted. When no popup is open the
                // handler is disabled and back falls through to the screen
                // handler above. (Upgrading activity to 1.12+ would give
                // ComponentActivity its own NavigationEventDispatcherOwner
                // and make this bridge unnecessary, but that is a bigger
                // dependency change.)
                var dropdownOpen by remember { mutableStateOf(false) }
                val navDispatcher = remember { NavigationEventDispatcher() }
                val navBackInput = remember {
                    object : NavigationEventInput() {
                        fun sendBack() {
                            dispatchOnBackStarted(NavigationEvent())
                            dispatchOnBackProgressed(
                                NavigationEvent(progress = 1f),
                            )
                            dispatchOnBackCompleted()
                        }
                    }
                }
                DisposableEffect(navDispatcher, navBackInput) {
                    navDispatcher.addInput(navBackInput)
                    onDispose { navDispatcher.removeInput(navBackInput) }
                }
                BackHandler(enabled = dropdownOpen) {
                    navBackInput.sendBack()
                }
                val navDispatcherOwner = remember {
                    object : NavigationEventDispatcherOwner {
                        override val navigationEventDispatcher: NavigationEventDispatcher
                            get() = navDispatcher
                    }
                }
                CompositionLocalProvider(
                    LocalNavigationEventDispatcherOwner provides navDispatcherOwner,
                ) {
                    MiuixTheme(controller = themeController) {
                        Scaffold(
                            topBar = {
                                SmallTopAppBar(
                                    title = when (screen) {
                                        Screen.Main -> getString(R.string.app_name)
                                        Screen.About -> getString(R.string.about_title)
                                        Screen.Tuning -> getString(R.string.tuning_title)
                                        Screen.Theme -> getString(R.string.theme_title)
                                    },
                                    navigationIcon = {
                                        if (screen != Screen.Main) {
                                            BackButton { screen = Screen.Main }
                                        }
                                    },
                                )
                            },
                            content = { paddingValues ->
                                // Page transition: entering a sub-screen slides
                                // in from the right, going back slides in from
                                // the left — the standard Android page pattern.
                                AnimatedContent(
                                    targetState = screen,
                                    transitionSpec = {
                                        val forward = initialState == Screen.Main
                                                && targetState != Screen.Main
                                        if (forward) {
                                            (slideInHorizontally { it } + fadeIn())
                                                .togetherWith(
                                                    slideOutHorizontally { -it } + fadeOut()
                                                )
                                        } else {
                                            (slideInHorizontally { -it } + fadeIn())
                                                .togetherWith(
                                                    slideOutHorizontally { it } + fadeOut()
                                                )
                                        }
                                    },
                                    label = "screen",
                                ) { currentScreen ->
                                    when (currentScreen) {
                                        Screen.Main -> MainScreen(
                                            paddingValues = paddingValues,
                                            onNavigate = { screen = it },
                                        )
                                        Screen.About -> AboutScreen(paddingValues = paddingValues)
                                        Screen.Tuning -> TuningScreen(paddingValues = paddingValues)
                                        Screen.Theme -> ThemeScreen(
                                            paddingValues = paddingValues,
                                            themeMode = themeMode,
                                            onThemeModeChange = { mode ->
                                                themeMode = mode
                                                writeTuningValue(context, "themeMode", mode)
                                            },
                                            onDropdownExpandedChange = { expanded ->
                                                dropdownOpen = expanded
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
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

/** MIUIX back button: IconButton + the Back icon, flipped for RTL. */
@Composable
private fun BackButton(onClick: () -> Unit) {
    val layoutDirection = LocalLayoutDirection.current
    IconButton(onClick = onClick) {
        Icon(
            modifier = Modifier.graphicsLayer {
                if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
            },
            imageVector = MiuixIcons.Back,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}

/** The main menu: 关于 / 微调 / 主题, each opening its own screen. */
@Composable
private fun MainScreen(
    paddingValues: PaddingValues,
    onNavigate: (Screen) -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(paddingValues)
            .padding(horizontal = 12.dp),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            BasicComponent(
                title = LocalContext.current.getString(R.string.tuning_title),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MiuixTheme.colorScheme.onBackground,
                    )
                },
                endActions = {
                    Icon(
                        imageVector = MiuixIcons.Basic.ArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                },
                onClick = { onNavigate(Screen.Tuning) },
            )
            BasicComponent(
                title = LocalContext.current.getString(R.string.theme_title),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Theme,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MiuixTheme.colorScheme.onBackground,
                    )
                },
                endActions = {
                    Icon(
                        imageVector = MiuixIcons.Basic.ArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                },
                onClick = { onNavigate(Screen.Theme) },
            )
            BasicComponent(
                title = LocalContext.current.getString(R.string.about_title),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Info,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MiuixTheme.colorScheme.onBackground,
                    )
                },
                endActions = {
                    Icon(
                        imageVector = MiuixIcons.Basic.ArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                },
                onClick = { onNavigate(Screen.About) },
            )
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

@Composable
private fun TuningScreen(paddingValues: PaddingValues) {
    val context = LocalContext.current
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
        mutableIntStateOf(readTuningValue(context, "pillSideInsetDp", 30))
    }
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(paddingValues)
            .padding(horizontal = 12.dp),
    ) {
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
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
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
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
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
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
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
                        pillSide = 30
                        writeTuningValue(context, "barOffsetDp", 24)
                        writeTuningValue(context, "pillExtraHeightDp", 12)
                        writeTuningValue(context, "pillSideInsetDp", 30)
                    },
                )
            }
        }
    }
}

/**
 * Theme screen: the unselected tabs' icon/label colour. 0 follows the
 * system, 1 forces light (black), 2 forces dark (white). The choice is
 * written to the module app's prefs; PDD picks it up on the next home
 * onResume, no reinstall needed.
 *
 * <p>The selector is MIUIX's OverlayDropdownPreference — the same
 * component KernelSU's manager uses for its dropdown settings (e.g. the
 * mount-namespace picker in RootProfileConfigMiuix). It lives in
 * miuix-preference, whose 0.9.4 AAR now sits in ~/tools/compose-deps/
 * alongside the other MIUIX modules, so the manual build picks it up.
 */
@Composable
private fun ThemeScreen(
    paddingValues: PaddingValues,
    themeMode: Int,
    onThemeModeChange: (Int) -> Unit,
    onDropdownExpandedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(paddingValues)
            .padding(horizontal = 12.dp),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            OverlayDropdownPreference(
                title = context.getString(R.string.theme_title),
                summary = context.getString(R.string.theme_summary),
                items = listOf(
                    context.getString(R.string.theme_system),
                    context.getString(R.string.theme_light),
                    context.getString(R.string.theme_dark),
                ),
                selectedIndex = themeMode,
                onSelectedIndexChange = { index -> onThemeModeChange(index) },
                onExpandedChange = onDropdownExpandedChange,
            )
        }
        Text(
            text = context.getString(R.string.theme_hint),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, start = 4.dp, end = 4.dp),
        )
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
