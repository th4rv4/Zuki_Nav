package com.th4rv4.zukinav.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.th4rv4.zukinav.R
import com.th4rv4.zukinav.update.Updater

/** About Zuki Nav and its developer, links to the project, and Support The Developer. */
class AboutScreen(host: MainActivity) : Screen(host) {

    override val view: View

    init {
        val back = ui.text("‹", 30f, Palette.TEXT).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Back to Settings"
            setPadding(ui.dp(4), 0, ui.dp(14), ui.dp(4))
            setOnClickListener { host.showTab(MainActivity.Tab.SETTINGS) }
        }
        val header = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back)
            addView(ui.text("About & Support", 22f, Palette.TEXT, Ui.Weight.BOLD),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        view = host.page(
            header,
            aboutCard(),
            ui.listCard(
                linkRow(R.drawable.ic_code, "Source Code", "github.com/${Updater.REPO}", "https://github.com/${Updater.REPO}"),
                linkRow(R.drawable.ic_globe, "Website", "th4rv4.github.io/Zuki_Nav", WEBSITE),
                linkRow(R.drawable.ic_bug, "Report A Bug", "Opens GitHub Issues", "https://github.com/${Updater.REPO}/issues"),
            ),
            supportCard(),
            ui.text("Unofficial, not affiliated with Suzuki", 12f, Palette.MUTED).apply { gravity = Gravity.CENTER },
        )
    }

    private fun aboutCard(): LinearLayout {
        val icon = android.widget.ImageView(ui.ctx).apply {
            setImageResource(R.drawable.ic_launcher)
            contentDescription = null
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, ui.dp(16).toFloat())
                }
            }
            clipToOutline = true
        }
        val iconRow = LinearLayout(ui.ctx).apply {
            gravity = Gravity.CENTER
            addView(icon, LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)))
        }
        return ui.card(
            iconRow,
            ui.vstack(
                2,
                ui.text("Zuki Nav", 18f, Palette.TEXT, Ui.Weight.BOLD).apply { gravity = Gravity.CENTER },
                ui.text("v${host.versionName()} · Free and open source · MIT licence", 12f, Palette.MUTED).apply { gravity = Gravity.CENTER },
            ),
            ui.text(DEVELOPER, 13f, Palette.TEXT_SOFT).apply { gravity = Gravity.CENTER },
            gapDp = 10,
            padDp = 18,
        )
    }

    private fun linkRow(icon: Int, title: String, subtitle: String, url: String): LinearLayout =
        ui.row(
            title, subtitle,
            trailing = ui.text("↗", 16f, Palette.MUTED),
            leading = ui.iconTile(icon, 32, 18, Palette.ACCENT_SOFT, Palette.ACCENT_BG, 10),
        ).apply {
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Palette.RIPPLE), null, ui.rounded(android.graphics.Color.WHITE, 8),
            )
            setOnClickListener { host.openUrl(url) }
        }

    private fun supportCard(): LinearLayout {
        val pay = ui.button("Pay With UPI", Ui.Btn.PRIMARY, 46) { payWithUpi() }
        val copy = ui.button("Copy ID", Ui.Btn.SECONDARY, 46) { copyUpi() }
        return ui.card(
            ui.vstack(
                2,
                ui.text("Support The Developer", 15f, Palette.TEXT, Ui.Weight.BOLD),
                ui.text("Zuki Nav is free and always will be. If it made your rides better, a small UPI tip keeps it going.", 12f, Palette.MUTED),
            ),
            ui.text(UPI_ID, 16f, Palette.TEXT, Ui.Weight.MEDIUM).apply { setTextIsSelectable(true) },
            ui.hstack(8, pay, copy, weighted = true),
            gapDp = 12,
        ).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Accents.withAlpha(Palette.ACCENT, 0x24), Accents.withAlpha(Palette.ACCENT_END, 0x24)),
            ).apply {
                cornerRadius = ui.dp(20).toFloat()
                setStroke(ui.dp(1), Accents.withAlpha(Palette.ACCENT_END, 0x45))
            }
        }
    }

    /** Opens any UPI app (GPay, PhonePe, Paytm…) with the ID filled in. */
    private fun payWithUpi() {
        val uri = android.net.Uri.parse("upi://pay?pa=$UPI_ID&pn=Zuki%20Nav&cu=INR&tn=Support%20Zuki%20Nav")
        try {
            host.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_VIEW, uri), "Pay With"))
        } catch (e: Exception) {
            host.toast("No UPI app found. Copy the ID instead.")
        }
    }

    private fun copyUpi() {
        val cm = host.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("UPI ID", UPI_ID))
        // Android 13 and up shows its own "Copied" message.
        if (android.os.Build.VERSION.SDK_INT < 33) host.toast("UPI ID copied")
    }


    private companion object {
        const val UPI_ID = "atharvaagnihotri@upi"
        const val WEBSITE = "https://th4rv4.github.io/Zuki_Nav/"
        const val DEVELOPER = "Made by Atharva (th4rv4), a 21-year-old Android developer with a passion for riding, " +
            "currently rocking his Gixxer SF 250. He built Zuki Nav to get Google Maps directions on his own " +
            "Ride Connect dash, and kept adding what he wanted on every ride since."
    }
}
