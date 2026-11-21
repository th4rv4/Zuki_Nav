package com.th4rv4.zukinav.sim

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import com.th4rv4.zukinav.ui.SettingsScreen
import com.th4rv4.zukinav.ui.SetupChecklist
import com.th4rv4.zukinav.util.SimHooks

/** Runs when Zuki Sim starts, before anything else, and plugs in [SimLink]. */
class SimInit : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return false
        SimHooks.onSimFrame = { SimLink.onFrame(ctx, it) }
        SetupChecklist.testChecks = { SimChecks.items(it) }
        SimHooks.findDash = { done -> SimFinder.find(ctx, done) }
        SettingsScreen.fakeAlertsPage = { FakeAlertsPage.build(it) }
        return true
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
