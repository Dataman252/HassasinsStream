package com.wifistream.receiver

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

enum class ThemeMode(val label: String) { SYSTEM("Match the phone"), LIGHT("Light"), DARK("Dark") }

/**
 * The three glass looks, after OriginOS 7's choices: Liquid (clear with a bright, light-catching
 * rim), Clear (barely there) and Frosted (milky, most readable).
 */
enum class GlassStyle(val label: String) { LIQUID("Liquid"), CLEAR("Clear"), FROSTED("Frosted") }

/** How see-through glass is: a multiplier on every glass fill, in the app and over video. */
enum class Clarity(val label: String, val fill: Float) {
    SOLID("Solid", 1.7f),
    BALANCED("Balanced", 1f),
    CLEAR("Clear", 0.6f),
    CRYSTAL("Crystal", 0.32f),
}

/**
 * Colour themes. The presets borrow the colour moods of well-known streaming services; they do not
 * use those services' names or logos in the app itself.
 */
enum class ColorPreset(
    val label: String,
    val note: String,
    val accent: Long,
    val accentLight: Long,
    val brand: List<Long>,
    val darkBase: List<Long>,
    val lightBase: List<Long>,
) {
    HASSASIN("Hassasin", "Violet to cyan", 0xFF67E8F9, 0xFF0E7490,
        listOf(0xFF8B5CF6, 0xFF6366F1, 0xFF22D3EE), listOf(0xFF0F0F23, 0xFF07080F, 0xFF030409), listOf(0xFFDDE3EE, 0xFFE4E9F2, 0xFFD3DAE8)),
    CINEMA_RED("Cinema Red", "Netflix style: red on black", 0xFFFF4D4D, 0xFFB20710,
        listOf(0xFF8C0610, 0xFFE50914, 0xFFFF6B6B), listOf(0xFF1A0A0B, 0xFF0A0A0A, 0xFF000000), listOf(0xFFEBE3E3, 0xFFF1ECEC, 0xFFE2D8D8)),
    PRIME_BLUE("Prime Blue", "Prime Video style: bright blue on navy", 0xFF38BDF8, 0xFF0369A1,
        listOf(0xFF0F79AF, 0xFF00A8E1, 0xFF7DD3FC), listOf(0xFF1B2530, 0xFF0F171E, 0xFF070B0F), listOf(0xFFDCE5EE, 0xFFE3EAF2, 0xFFD2DCE8)),
    STAR_BLUE("Star Blue", "JioHotstar style: blue into violet", 0xFF60A5FA, 0xFF1D4ED8,
        listOf(0xFF1F80E0, 0xFF5B4BF5, 0xFFC13BF7), listOf(0xFF141B2E, 0xFF0C111B, 0xFF05070D), listOf(0xFFDFE3F2, 0xFFE6E9F5, 0xFFD6DBEE)),
    STUDIO("Studio", "Apple TV+ style: quiet monochrome", 0xFFF5F5F7, 0xFF1D1D1F,
        listOf(0xFF3A3A3C, 0xFF8E8E93, 0xFFE5E5EA), listOf(0xFF161618, 0xFF0A0A0B, 0xFF000000), listOf(0xFFE8E8ED, 0xFFF2F2F7, 0xFFE0E0E6)),
    SPOTLIGHT_GOLD("Spotlight Gold", "Warm gold on deep black", 0xFFF5B14C, 0xFF9A5B00,
        listOf(0xFF7C4A12, 0xFFF5B14C, 0xFFFDE68A), listOf(0xFF1C1917, 0xFF0C0A09, 0xFF000000), listOf(0xFFEDE6DC, 0xFFF4EFE8, 0xFFE4DBCD)),
    MATERIAL_LILAC("Material Lilac", "Soft tonal purple", 0xFFD0BCFF, 0xFF6750A4,
        listOf(0xFF4F378B, 0xFF7F67BE, 0xFFD0BCFF), listOf(0xFF1D1B20, 0xFF141218, 0xFF0F0D13), listOf(0xFFE7E0EC, 0xFFFEF7FF, 0xFFE6DFEB)),
    BENTO_BLUE("Bento Blue", "Clear blue on graphite tiles", 0xFF7AA2FF, 0xFF2952CC,
        listOf(0xFF334A7A, 0xFF5B8CFF, 0xFFA9C1FF), listOf(0xFF111114, 0xFF0B0B0E, 0xFF050507), listOf(0xFFE4E4E7, 0xFFF4F4F5, 0xFFDCDCE0)),
    CUSTOM("Custom", "Your own accent colour", 0, 0, emptyList(), listOf(0xFF101018, 0xFF08080C, 0xFF030305), listOf(0xFFE1E5EE, 0xFFE8EBF2, 0xFFD8DDE8)),
}

/**
 * The four designs of the whole app. Each one brings its own colours and type; the colours can
 * still be changed under Look afterwards. Every device keeps its own choice.
 */
enum class AppLayout(val label: String, val note: String, val preset: ColorPreset) {
    GLASS("Glass", "Floating glass menu, large hero, soft backdrop", ColorPreset.HASSASIN),
    SPOTLIGHT("Spotlight", "Full-screen artwork, side menu, bold titles", ColorPreset.SPOTLIGHT_GOLD),
    MATERIAL("Material", "Featured carousel, Top picks and tidy rows", ColorPreset.MATERIAL_LILAC),
    BENTO("Bento", "Tiles of different sizes; pause screen in tiles", ColorPreset.BENTO_BLUE),
}

/** Accent colours for the Custom theme. */
val CUSTOM_ACCENTS: List<Pair<String, Long>> = listOf(
    "Cyan" to 0xFF22D3EE, "Violet" to 0xFFA78BFA, "Pink" to 0xFFF472B6, "Red" to 0xFFF87171,
    "Orange" to 0xFFFB923C, "Gold" to 0xFFFBBF24, "Green" to 0xFF4ADE80, "Teal" to 0xFF2DD4BF, "Blue" to 0xFF60A5FA,
)

/** Launcher icons: the Hassasin mark in each theme's colours. Each is an activity alias in the manifest. */
enum class AppIcon(val label: String, val alias: String, val colours: List<Long>) {
    CLASSIC("Classic", ".IconClassic", listOf(0xFF8B5CF6, 0xFF22D3EE)),
    RED("Red", ".IconRed", listOf(0xFF8C0610, 0xFFE50914)),
    BLUE("Blue", ".IconBlue", listOf(0xFF0F79AF, 0xFF00A8E1)),
    STAR("Star", ".IconStar", listOf(0xFF1F80E0, 0xFFC13BF7)),
    MONO("Mono", ".IconMono", listOf(0xFF1C1C1E, 0xFF636366)),
}

/** The look of the app. Read anywhere; screens redraw when it changes. */
object AppTheme {
    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set
    var glass by mutableStateOf(GlassStyle.LIQUID)
        private set
    var clarity by mutableStateOf(Clarity.BALANCED)
        private set
    var preset by mutableStateOf(ColorPreset.HASSASIN)
        private set
    var customAccent by mutableStateOf(0)
        private set
    var icon by mutableStateOf(AppIcon.CLASSIC)
        private set

    var layout by mutableStateOf(AppLayout.GLASS)
        private set

    /** Switches the design, and its matching colours. */
    fun changeLayout(context: Context, choice: AppLayout) {
        layout = choice
        context.getSharedPreferences("look", Context.MODE_PRIVATE).edit().putString("layout", choice.name).apply()
        change(context, preset = choice.preset)
    }

    /** Phones only, for testing: lay the app out as it appears on a TV. */
    var tvLayout by mutableStateOf(false)
        private set

    fun setTvLayout(context: Context, on: Boolean) {
        tvLayout = on
        context.getSharedPreferences("look", Context.MODE_PRIVATE).edit().putBoolean("tv_layout", on).apply()
    }

    /** Whether the phone itself is in dark mode. */
    var systemDark by mutableStateOf(true)

    /** The player is always dark: video looks best on black, and its panels sit over moving pictures. */
    var forceDark by mutableStateOf(false)

    val light: Boolean
        get() = !forceDark && when (mode) {
            ThemeMode.SYSTEM -> !systemDark
            ThemeMode.LIGHT -> true
            ThemeMode.DARK -> false
        }

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("look", Context.MODE_PRIVATE)
        fun <T : Enum<T>> read(key: String, values: List<T>, default: T) = values.firstOrNull { it.name == prefs.getString(key, null) } ?: default
        mode = read("mode", ThemeMode.entries, ThemeMode.SYSTEM)
        glass = read("glass", GlassStyle.entries, GlassStyle.LIQUID)
        clarity = read("clarity", Clarity.entries, Clarity.BALANCED)
        preset = read("preset", ColorPreset.entries, ColorPreset.HASSASIN)
        icon = read("icon", AppIcon.entries, AppIcon.CLASSIC)
        customAccent = prefs.getInt("accent", 0).coerceIn(0, CUSTOM_ACCENTS.lastIndex)
        tvLayout = prefs.getBoolean("tv_layout", false)
        layout = read("layout", AppLayout.entries, AppLayout.GLASS)
    }

    fun change(
        context: Context,
        mode: ThemeMode = this.mode,
        glass: GlassStyle = this.glass,
        clarity: Clarity = this.clarity,
        preset: ColorPreset = this.preset,
        customAccent: Int = this.customAccent,
    ) {
        this.mode = mode
        this.glass = glass
        this.clarity = clarity
        this.preset = preset
        this.customAccent = customAccent
        context.getSharedPreferences("look", Context.MODE_PRIVATE).edit()
            .putString("mode", mode.name).putString("glass", glass.name).putString("clarity", clarity.name)
            .putString("preset", preset.name).putInt("accent", customAccent).apply()
    }

    /** Switches the launcher icon. The home screen may take a few seconds to show it. */
    /** Chosen while the app is open; applied when it goes to the background (see [applyPendingIcon]). */
    private var pendingIcon: AppIcon? = null

    /**
     * Remembers the new icon. Switching launcher entries closes the app on many phones, so the
     * switch itself waits until the app leaves the screen.
     */
    fun changeIcon(context: Context, choice: AppIcon) {
        icon = choice
        pendingIcon = choice
        context.getSharedPreferences("look", Context.MODE_PRIVATE).edit().putString("icon", choice.name).apply()
    }

    fun applyPendingIcon(context: Context) {
        val choice = pendingIcon ?: return
        pendingIcon = null
        val pm = context.packageManager
        // Enable the new one first, so there is never a moment with no launcher entry.
        val ordered = listOf(choice) + AppIcon.entries.filter { it != choice }
        ordered.forEach { option ->
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, context.packageName + option.alias),
                if (option == choice) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}

// Palette chosen with the ui-ux-pro-max design data: cinema-dark base with depth, slate secondary
// text and one accent. Text pairs stay at 4.5:1 or better in both themes.

private val customAccentColor: Color get() = Color(CUSTOM_ACCENTS[AppTheme.customAccent].second)

/** The brand gradient: three stops from the colour theme. */
val BrandGradient: List<Color>
    get() = if (AppTheme.preset == ColorPreset.CUSTOM) {
        listOf(lerp(customAccentColor, Color.Black, 0.35f), customAccentColor, lerp(customAccentColor, Color.White, 0.35f))
    } else {
        AppTheme.preset.brand.map { Color(it) }
    }

val Violet: Color get() = BrandGradient[0]
val Indigo: Color get() = BrandGradient[1]
val Cyan: Color get() = BrandGradient[2]

/** Page colours: top, middle and bottom of the backdrop. */
val BaseGradient: List<Color>
    get() = (if (AppTheme.light) AppTheme.preset.lightBase else AppTheme.preset.darkBase).map { Color(it) }

/** Page colour behind everything. */
val Night: Color get() = BaseGradient[1]

/** Main text colour. */
val Ink: Color get() = if (AppTheme.light) Color(0xFF0B1020) else Color(0xFFF8FAFC)

/** Secondary text. */
val Muted: Color get() = if (AppTheme.light) Color(0xFF475569) else Color(0xFFA8B3C7)

val Panel: Color get() = if (AppTheme.light) Color(0x14000000) else Color(0x1FFFFFFF)
val PanelFocused: Color get() = if (AppTheme.light) Color(0x260B1020) else Color(0x40FFFFFF)

/** The one highlight colour: focus rings, selections, progress. Deeper in light mode so it stays readable. */
val Accent: Color
    get() = if (AppTheme.preset == ColorPreset.CUSTOM) {
        if (AppTheme.light) lerp(customAccentColor, Color.Black, 0.45f) else customAccentColor
    } else {
        Color(if (AppTheme.light) AppTheme.preset.accentLight else AppTheme.preset.accent)
    }

/** Text and icons drawn on top of [Accent]. */
val OnAccent: Color get() = if (Accent.luminance() > 0.4f) Color(0xFF07080F) else Color.White

/** Older name for [Accent], still used across the screens. */
val Amber: Color get() = Accent

/** The off position of a switch. */
val SwitchOff: Color get() = if (AppTheme.light) Color(0x330B1020) else Color(0x33FFFFFF)

/** Dark glass for panels over moving video, made more or less see-through by the transparency setting. */
val GlassDark: Color get() = Color(0x9E0B0B1A).glassy()

/** A glass fill colour, made more or less see-through by the transparency setting. */
fun Color.glassy(): Color = copy(alpha = (alpha * AppTheme.clarity.fill).coerceIn(0f, 1f))
