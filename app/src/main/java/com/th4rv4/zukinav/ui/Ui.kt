package com.th4rv4.zukinav.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * The app's colours. [apply] picks the theme (Dark, AMOLED or Light) and the
 * accent before the screens are built.
 */
object Palette {
    var isLight = false
        private set
    var isAmoled = false
        private set

    /** Opaque surface for sheets and dialogs. */
    var SHEET = 0
    var BG = 0; var SURFACE = 0; var BORDER = 0; var INSET = 0; var INPUT_BORDER = 0; var NAV_BG = 0
    var TEXT = 0; var TEXT_SOFT = 0; var MUTED = 0
    /** Accent: [ACCENT] is the start colour, [ACCENT_SOFT] the one used for text and icons. */
    var ACCENT = 0; var ACCENT_SOFT = 0; var ACCENT_BG = 0
    /** The three gauge colours, start to end, and the end of the button gradient. */
    var ACCENT_MID = 0; var ACCENT_END = 0; var BUTTON_END = 0
    /** Text on accent buttons. */
    var ON_ACCENT = 0
    /** Glow around accent rings and arcs; 0 in Light. */
    var ACCENT_GLOW = 0
    /** Background glows (see AuroraBackground); 0 in AMOLED. */
    var BG_GLOW_1 = 0; var BG_GLOW_2 = 0
    var OK = 0; var OK_BG = 0; var OK_BORDER = 0
    var WARN = 0; var WARN_BG = 0; var WARN_BORDER = 0
    var DANGER = 0; var DANGER_BG = 0; var DANGER_BORDER = 0
    /** Icon tile behind the bike, step badges. */
    var TILE = 0
    /** Line above the tab bar. */
    var DIVIDER = 0
    /** Touch feedback over surfaces. */
    var RIPPLE = 0
    var SWITCH_THUMB_OFF = 0
    // Dash Test marks.
    var MARK_RIGHT_FG = 0; var MARK_WRONG_BG = 0; var MARK_WRONG_FG = 0; var UNTESTED_BORDER = 0
    // Live dash panel on the Ride tab.
    var PANEL_TOP = 0; var PANEL_BOTTOM = 0; var GLASS = 0; var GLASS_BORDER = 0; var TRACK = 0

    enum class Mode { DARK, AMOLED, LIGHT }

    init { apply(null, Mode.DARK, Accents.DEFAULT) }

    fun apply(ctx: Context?, mode: Mode, accentKey: String) {
        val light = mode == Mode.LIGHT
        val amoled = mode == Mode.AMOLED
        isLight = light
        isAmoled = amoled
        fun c(dark: Long, amoledColor: Long, lightColor: Long) =
            (if (light) lightColor else if (amoled) amoledColor else dark).toInt()
        BG = c(0xFF070A10, 0xFF000000, 0xFFF2F4F9)
        SURFACE = c(0x0FFFFFFF, 0xFF0A0B0E, 0xC7FFFFFF)
        BORDER = c(0x17FFFFFF, 0xFF18191E, 0x12101828)
        INSET = c(0x0AFFFFFF, 0xFF050506, 0x08101828)
        INPUT_BORDER = c(0x21FFFFFF, 0xFF1F2026, 0x1F101828)
        SHEET = c(0xFF0D111A, 0xFF0A0B0E, 0xFFFFFFFF)
        NAV_BG = c(0xFF0A0D14, 0xFF000000, 0xFFF7F8FB)
        TEXT = c(0xFFEEF2F8, 0xFFF2F4F8, 0xFF0E1422)
        TEXT_SOFT = c(0xFFC9D0DC, 0xFFC9CFD9, 0xFF2E3746)
        MUTED = c(0xFF8792A6, 0xFF7C8496, 0xFF667085)
        OK = c(0xFF4ADE9A, 0xFF4ADE9A, 0xFF16884A)
        OK_BG = c(0x244ADE9A, 0x244ADE9A, 0xFFE3F6EA)
        OK_BORDER = c(0x474ADE9A, 0x474ADE9A, 0xFFA9DEBE)
        WARN = c(0xFFFFC966, 0xFFFFC966, 0xFF8F5D00)
        WARN_BG = c(0x24FFC966, 0x24FFC966, 0xFFFFF3D6)
        WARN_BORDER = c(0x47FFC966, 0x47FFC966, 0xFFEBCB86)
        DANGER = c(0xFFFF7A8A, 0xFFFF7A8A, 0xFFC62F2F)
        DANGER_BG = c(0x1FFF5A6E, 0x1FFF5A6E, 0xFFFDECEC)
        DANGER_BORDER = c(0x47FF5A6E, 0x47FF5A6E, 0xFFF2B8B8)
        TILE = c(0x14FFFFFF, 0xFF121318, 0x0F101828)
        DIVIDER = c(0x14FFFFFF, 0xFF141519, 0x14101828)
        RIPPLE = c(0x22FFFFFF, 0x22FFFFFF, 0x1A000000)
        SWITCH_THUMB_OFF = c(0xFF8792A6, 0xFF7C8496, 0xFFFFFFFF)
        MARK_RIGHT_FG = c(0xFFBFF3D4, 0xFFBFF3D4, 0xFF0E5A30)
        MARK_WRONG_BG = c(0x3DFF5A6E, 0x3DFF5A6E, 0xFFFBE0E3)
        MARK_WRONG_FG = c(0xFFFFB3BD, 0xFFFFB3BD, 0xFFA3192B)
        UNTESTED_BORDER = c(0x21FFFFFF, 0xFF1F2026, 0x1F101828)
        PANEL_TOP = SURFACE
        PANEL_BOTTOM = SURFACE
        GLASS = c(0x0DFFFFFF, 0xFF0F1014, 0x0A101624)
        GLASS_BORDER = c(0x17FFFFFF, 0xFF1C1D22, 0x14101624)
        TRACK = c(0x17FFFFFF, 0xFF16171C, 0x17101624)

        val a = Accents.resolve(ctx, accentKey, light)
        ACCENT = a[0]
        ACCENT_MID = a[1]
        ACCENT_END = a[2]
        ON_ACCENT = a[3]
        BUTTON_END = Accents.blend(a[0], a[2], 0.45f)
        ACCENT_SOFT = if (light) a[0] else a[1]
        ACCENT_BG = Accents.withAlpha(ACCENT, if (light) 0x1F else 0x2E)
        ACCENT_GLOW = if (light) 0 else Accents.withAlpha(ACCENT_MID, 0x8C)
        BG_GLOW_1 = when (mode) { Mode.DARK -> Accents.withAlpha(ACCENT, 0x61); Mode.LIGHT -> Accents.withAlpha(ACCENT, 0x29); Mode.AMOLED -> 0 }
        BG_GLOW_2 = when (mode) { Mode.DARK -> Accents.withAlpha(ACCENT_END, 0x29); Mode.LIGHT -> Accents.withAlpha(ACCENT_END, 0x1A); Mode.AMOLED -> 0 }
    }

    /** Older callers: Dark or Light with the default accent. */
    fun apply(light: Boolean) = apply(null, if (light) Mode.LIGHT else Mode.DARK, Accents.DEFAULT)
}

/** Small view factory so every screen shares one look. Built in code; no AndroidX. */
class Ui(val ctx: Context) {

    enum class Weight { REGULAR, MEDIUM, BOLD }
    enum class Btn { PRIMARY, SECONDARY, DANGER, OK, WARN }

    private val density = ctx.resources.displayMetrics.density
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    fun rounded(color: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    /** The accent gradient, start to [Palette.BUTTON_END], left to right. */
    fun accentGradient(radiusDp: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Palette.ACCENT, Palette.BUTTON_END)).apply {
            cornerRadius = dp(radiusDp).toFloat()
        }

    /** Accent gradient with touch ripple, for main buttons. */
    fun accentPressable(radiusDp: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(Palette.RIPPLE), accentGradient(radiusDp), rounded(Color.WHITE, radiusDp))

    /** Background with touch ripple. */
    fun pressable(color: Int, radiusDp: Int, stroke: Int? = null): Drawable =
        RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE),
            rounded(color, radiusDp, stroke),
            rounded(Color.WHITE, radiusDp),
        )

    private fun gap(dpSize: Int, color: Int = Color.TRANSPARENT): Drawable =
        GradientDrawable().apply {
            setColor(color)
            setSize(dp(dpSize), dp(dpSize))
        }

    fun text(
        s: CharSequence,
        sizeSp: Float = 15f,
        color: Int = Palette.TEXT,
        weight: Weight = Weight.REGULAR,
    ): TextView = TextView(ctx).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        typeface = when (weight) {
            Weight.REGULAR -> Typeface.DEFAULT
            Weight.MEDIUM -> medium
            Weight.BOLD -> Typeface.DEFAULT_BOLD
        }
        setLineSpacing(0f, 1.15f)
    }

    fun title(s: String) = text(s, 26f, Palette.TEXT, Weight.BOLD).apply { letterSpacing = -0.01f }

    fun sectionLabel(s: String) = text(s.uppercase(), 12f, Palette.MUTED, Weight.BOLD).apply {
        letterSpacing = 0.1f
        setPadding(dp(4), dp(4), dp(4), 0)
    }

    fun vstack(gapDp: Int, vararg views: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        if (gapDp > 0) {
            dividerDrawable = gap(gapDp)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        }
        views.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }

    /** Horizontal row; views passed in [weighted] fill the space evenly. */
    fun hstack(gapDp: Int, vararg views: View, weighted: Boolean = false): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (gapDp > 0) {
            dividerDrawable = gap(gapDp)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        }
        views.forEach {
            val lp = if (weighted) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            addView(it, lp)
        }
    }

    /** A flexible spacer for [hstack]. */
    fun spring(): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    fun card(vararg views: View, gapDp: Int = 12, padDp: Int = 16, radiusDp: Int = 20): LinearLayout =
        vstack(gapDp, *views).apply {
            background = rounded(Palette.SURFACE, radiusDp, Palette.BORDER)
            setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
        }

    /** A card of rows separated by hairlines. */
    fun listCard(vararg rows: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.SURFACE, 20, Palette.BORDER)
        setPadding(dp(16), dp(2), dp(16), dp(2))
        dividerDrawable = GradientDrawable().apply { setColor(Palette.BORDER); setSize(1, dp(1)) }
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        rows.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }

    /** Title (+ optional subtitle) on the left, [trailing] on the right. */
    fun row(title: String, subtitle: String? = null, trailing: View? = null, leading: View? = null): LinearLayout {
        val texts = vstack(2, text(title, 15f, Palette.TEXT, Weight.MEDIUM)).apply {
            if (subtitle != null) addView(text(subtitle, 12f, Palette.MUTED))
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(0, dp(10), 0, dp(10))
            if (leading != null) {
                addView(leading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) })
            }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (trailing != null) {
                addView(trailing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
            }
        }
    }

    /** A row that is itself a button, with a chevron or a value on the right. */
    fun navRow(title: String, value: String? = null, onClick: () -> Unit): LinearLayout =
        row(title, trailing = text(value ?: "›", if (value == null) 20f else 13f, Palette.MUTED)).apply {
            isClickable = true
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(Palette.RIPPLE), null, rounded(Color.WHITE, 8))
            setOnClickListener { onClick() }
        }

    fun switchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val sw = Switch(ctx).apply {
            isChecked = checked
            contentDescription = title
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, Palette.SWITCH_THUMB_OFF),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Palette.ACCENT, Palette.INPUT_BORDER),
            )
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        return row(title, subtitle, sw).apply { setOnClickListener { sw.toggle() } }
    }

    fun button(label: String, style: Btn = Btn.PRIMARY, heightDp: Int = 52, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            typeface = medium
            stateListAnimator = null
            minHeight = dp(heightDp)
            minimumHeight = dp(heightDp)
            setPadding(dp(14), 0, dp(14), 0)
            val (bg, fg, stroke) = when (style) {
                Btn.PRIMARY -> Triple(Palette.ACCENT, Palette.ON_ACCENT, null)
                Btn.SECONDARY -> Triple(Palette.SURFACE, Palette.TEXT, Palette.INPUT_BORDER)
                Btn.DANGER -> Triple(Palette.DANGER_BG, Palette.DANGER, Palette.DANGER_BORDER)
                Btn.OK -> Triple(Palette.OK_BG, Palette.OK, Palette.OK_BORDER)
                Btn.WARN -> Triple(Palette.WARN_BG, Palette.WARN, Palette.WARN_BORDER)
            }
            background = if (style == Btn.PRIMARY) accentPressable(16) else pressable(bg, 16, stroke)
            setTextColor(fg)
            setOnClickListener { onClick() }
        }

    /** Small rounded label, e.g. "Connected". */
    fun pill(label: String, fg: Int, bg: Int): TextView = text(label, 12f, fg, Weight.BOLD).apply {
        background = rounded(bg, 999)
        setPadding(dp(10), dp(5), dp(10), dp(5))
    }

    /** Round status badge: a tick, a "!" or a step number. */
    fun badge(kind: Badge): TextView = text("", 12f, Palette.TEXT, Weight.BOLD).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        setWidth(dp(26))
        setHeight(dp(26))
        setLineSpacing(0f, 1f)
        when (kind) {
            is Badge.Done -> { text = "✓"; setTextColor(Palette.OK); background = rounded(Palette.OK_BG, 13) }
            is Badge.Todo -> { text = "!"; setTextColor(Palette.WARN); background = rounded(Palette.WARN_BG, 13) }
            is Badge.Step -> { text = kind.n.toString(); setTextColor(Palette.MUTED); background = rounded(Color.TRANSPARENT, 13, Palette.INPUT_BORDER) }
        }
    }

    sealed class Badge {
        object Done : Badge()
        object Todo : Badge()
        class Step(val n: Int) : Badge()
    }

    fun icon(resId: Int, sizeDp: Int, tint: Int, description: String? = null): ImageView = ImageView(ctx).apply {
        setImageResource(resId)
        imageTintList = ColorStateList.valueOf(tint)
        contentDescription = description
        importantForAccessibility = if (description == null) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_YES
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        minimumWidth = dp(sizeDp)
        minimumHeight = dp(sizeDp)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    /** A boxed icon, as used for the bike and the guidance arrow. */
    fun iconTile(resId: Int, boxDp: Int, iconDp: Int, tint: Int, bg: Int, radiusDp: Int): LinearLayout =
        LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = rounded(bg, radiusDp)
            addView(icon(resId, iconDp, tint), LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)))
            layoutParams = LinearLayout.LayoutParams(dp(boxDp), dp(boxDp))
            minimumWidth = dp(boxDp)
            minimumHeight = dp(boxDp)
        }

    fun input(value: String, hint: String, numeric: Boolean = false, onChange: (String) -> Unit): EditText =
        EditText(ctx).apply {
            setText(value)
            this.hint = hint
            textSize = 14f
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.MUTED)
            background = rounded(Palette.INSET, 10, Palette.INPUT_BORDER)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            minWidth = dp(120)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            isSingleLine = true
            inputType = if (numeric) {
                android.text.InputType.TYPE_CLASS_NUMBER
            } else {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            }
            contentDescription = hint
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { onChange(s?.toString()?.trim().orEmpty()) }
            })
        }

    /** Two-option segmented control. Returns the view; [onPick] gets 0 or 1. */
    fun segmented(left: String, right: String, selected: Int, onPick: (Int) -> Unit): LinearLayout =
        segmented(listOf(left, right), selected, onPick = onPick)

    /** Segmented control; [onPick] gets the index of the chosen option. */
    /** [fill]: the options share the full width equally, for a control on its own line. */
    fun segmented(labels: List<String>, selected: Int, padDp: Int = 14, fill: Boolean = false, onPick: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Palette.INSET, 12, Palette.INPUT_BORDER)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val buttons = labels.map { label ->
            text(label, 13f, Palette.MUTED, Weight.BOLD).apply {
                gravity = Gravity.CENTER
                setPadding(dp(padDp), dp(7), dp(padDp), dp(7))
                isClickable = true
                isFocusable = true
                contentDescription = label
            }
        }
        fun paint(sel: Int) {
            buttons.forEachIndexed { i, b ->
                b.setTextColor(if (i == sel) Palette.ON_ACCENT else Palette.MUTED)
                b.background = if (i == sel) accentGradient(9) else null
                b.isSelected = i == sel
            }
        }
        buttons.forEachIndexed { i, b ->
            b.setOnClickListener { paint(i); onPick(i) }
            if (fill) {
                box.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            } else {
                box.addView(b)
            }
        }
        paint(selected)
        return box
    }

    /** Dot + label, e.g. "● Scanning". */
    fun dotLabel(label: String, color: Int): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            View(ctx).apply { background = rounded(color, 4) },
            LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) },
        )
        addView(text(label, 12f, color, Weight.MEDIUM))
    }

    /**
     * Grid of accent swatches, three per row. [onPick] gets the preset key.
     * The Wallpaper option shows only where the phone supports it.
     */
    fun accentPicker(selected: String, onPick: (String) -> Unit): LinearLayout {
        val keys = ArrayList<String>()
        if (Accents.wallpaperSupported) keys += Accents.WALLPAPER
        Accents.presets.forEach { keys += it.key }
        val grid = vstack(12)
        keys.chunked(3).forEach { rowKeys ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 3) {
                val key = rowKeys.getOrNull(i)
                if (key == null) { row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f)); continue }
                val a = Accents.resolve(ctx, key, Palette.isLight)
                val chosen = key == selected
                val cell = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(SwatchView(ctx, intArrayOf(a[0], a[1], a[2]), chosen),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
                    addView(text(Accents.name(key), 12f, if (chosen) Palette.TEXT else Palette.MUTED, if (chosen) Weight.BOLD else Weight.MEDIUM)
                        .apply { gravity = Gravity.CENTER },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
                }.apply {
                    isClickable = true
                    isFocusable = true
                    contentDescription = "Accent ${Accents.name(key)}"
                    isSelected = chosen
                    background = RippleDrawable(ColorStateList.valueOf(Palette.RIPPLE), null, rounded(Color.WHITE, 14))
                    setPadding(0, dp(4), 0, dp(4))
                    setOnClickListener { onPick(key) }
                }
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return grid
    }
}
