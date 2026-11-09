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

/** Zuki Nav colours (dark theme). */
/**
 * The app's colours. Dark is the default; [apply] switches every colour to
 * the light set before the screens are built.
 */
object Palette {
    var isLight = false
        private set

    var BG = 0; var SURFACE = 0; var BORDER = 0; var INSET = 0; var INPUT_BORDER = 0; var NAV_BG = 0
    var TEXT = 0; var TEXT_SOFT = 0; var MUTED = 0
    var ACCENT = 0; var ACCENT_SOFT = 0; var ACCENT_BG = 0
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

    init { apply(false) }

    fun apply(light: Boolean) {
        isLight = light
        fun c(dark: Long, lightColor: Long) = (if (light) lightColor else dark).toInt()
        BG = c(0xFF0B0E13, 0xFFF3F5F8)
        SURFACE = c(0xFF141922, 0xFFFFFFFF)
        BORDER = c(0xFF222937, 0xFFE1E5EB)
        INSET = c(0xFF0F141C, 0xFFEEF1F5)
        INPUT_BORDER = c(0xFF2B3342, 0xFFCDD3DC)
        NAV_BG = c(0xFF0E1218, 0xFFFFFFFF)
        TEXT = c(0xFFF2F4F8, 0xFF111723)
        TEXT_SOFT = c(0xFFC9D0DC, 0xFF2E3746)
        MUTED = c(0xFF9AA3B5, 0xFF5E6878)
        ACCENT = c(0xFF2D6BFF, 0xFF2D6BFF)
        ACCENT_SOFT = c(0xFF6FA0FF, 0xFF1F57E0)
        ACCENT_BG = c(0xFF121C30, 0xFFE7EEFF)
        OK = c(0xFF7CE3A6, 0xFF16884A)
        OK_BG = c(0xFF12291D, 0xFFE3F6EA)
        OK_BORDER = c(0xFF1F4A31, 0xFFA9DEBE)
        WARN = c(0xFFFFC966, 0xFF8F5D00)
        WARN_BG = c(0xFF2A2312, 0xFFFFF3D6)
        WARN_BORDER = c(0xFF4A3A1A, 0xFFEBCB86)
        DANGER = c(0xFFFF9B9B, 0xFFC62F2F)
        DANGER_BG = c(0xFF1C1416, 0xFFFDECEC)
        DANGER_BORDER = c(0xFF3A2A2A, 0xFFF2B8B8)
        TILE = c(0xFF1B2433, 0xFFE7EEFF)
        DIVIDER = c(0xFF1C222C, 0xFFE1E5EB)
        RIPPLE = c(0x22FFFFFF, 0x1A000000)
        SWITCH_THUMB_OFF = c(0xFFC9D0DC, 0xFFFFFFFF)
        MARK_RIGHT_FG = c(0xFFBFF3D4, 0xFF0E5A30)
        MARK_WRONG_BG = c(0xFF5A4416, 0xFFFBE7B5)
        MARK_WRONG_FG = c(0xFFFFE1A3, 0xFF6B4A00)
        UNTESTED_BORDER = c(0xFF3A4352, 0xFFCDD3DC)
    }
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
                Btn.PRIMARY -> Triple(Palette.ACCENT, Color.WHITE, null)
                Btn.SECONDARY -> Triple(Palette.SURFACE, Palette.TEXT, Palette.INPUT_BORDER)
                Btn.DANGER -> Triple(Palette.DANGER_BG, Palette.DANGER, Palette.DANGER_BORDER)
                Btn.OK -> Triple(Palette.OK_BG, Palette.OK, Palette.OK_BORDER)
                Btn.WARN -> Triple(Palette.WARN_BG, Palette.WARN, Palette.WARN_BORDER)
            }
            background = pressable(bg, 16, stroke)
            setTextColor(fg)
            setOnClickListener { onClick() }
        }

    /** Small rounded label, e.g. "Connected". */
    fun pill(label: String, fg: Int, bg: Int): TextView = text(label, 12f, fg, Weight.BOLD).apply {
        background = rounded(bg, 999)
        setPadding(dp(10), dp(5), dp(10), dp(5))
    }

    /** Round status badge: a tick, a "!" or a step number. */
    fun badge(kind: Badge): TextView = text("", 14f, Palette.TEXT, Weight.BOLD).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        setWidth(dp(28))
        setHeight(dp(28))
        when (kind) {
            is Badge.Done -> { text = "✓"; setTextColor(Palette.OK); background = rounded(Palette.OK_BG, 14) }
            is Badge.Todo -> { text = "!"; setTextColor(Palette.WARN); background = rounded(Palette.WARN_BG, 14) }
            is Badge.Step -> { text = kind.n.toString(); setTextColor(Palette.MUTED); background = rounded(Palette.TILE, 14) }
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
    fun segmented(labels: List<String>, selected: Int, padDp: Int = 14, onPick: (Int) -> Unit): LinearLayout {
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
                b.setTextColor(if (i == sel) Color.WHITE else Palette.MUTED)
                b.background = if (i == sel) rounded(Palette.ACCENT, 9) else null
                b.isSelected = i == sel
            }
        }
        buttons.forEachIndexed { i, b ->
            b.setOnClickListener { paint(i); onPick(i) }
            box.addView(b)
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
}
