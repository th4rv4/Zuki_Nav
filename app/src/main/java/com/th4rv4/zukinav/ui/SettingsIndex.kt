package com.th4rv4.zukinav.ui

/**
 * Every setting a rider might look for, with where it lives and other words
 * people use for it, for the search box at the top of Settings. Free of
 * Android so the matching can be tested.
 */
object SettingsIndex {

    /** Where a result leads. */
    sealed interface Target {
        data class Page(val key: String) : Target
        data class Bike(val section: Int) : Target
        data object Trips : Target
        data object Checklist : Target
    }

    data class Entry(
        val title: String,
        val detail: String,
        val path: String,
        val target: Target,
        /** Other words for it: what a rider might type instead. */
        val words: String = "",
    )

    private const val BIKE_FUEL = 0
    private const val BIKE_THERMALS = 1
    private const val BIKE_SERVICE = 2

    private fun page(key: String) = Target.Page(key)

    val entries: List<Entry> = listOf(
        // Rider
        Entry("Rider Name", "Shown on the dash when it connects", "Settings › Rider", page(SettingsKeys.RIDER), "name profile me avatar photo picture"),
        Entry("Change Bike", "Pick a different bike or pair again", "Settings › Rider", page(SettingsKeys.RIDER), "model pair scan gixxer access burgman avenis vstrom"),
        // Dash
        Entry("Speed Alert", "The dash warns above a speed", "Settings › Dash", page(SettingsKeys.DASH), "overspeed limit fast warning kmh"),
        Entry("Startup Animation", "Spins the arrow after Welcome", "Settings › Dash", page(SettingsKeys.DASH), "welcome spin intro boot"),
        Entry("Startup Greeting", "Your text on the dash at switch-on", "Settings › Dash", page(SettingsKeys.DASH), "welcome hello name caller message"),
        // Calls
        Entry("Call Alerts", "Incoming and missed calls on the dash", "Settings › Calls", page(SettingsKeys.CALLS), "phone ring caller whatsapp missed incoming"),
        Entry("Reject Unknown Callers", "Decline numbers not in your contacts", "Settings › Calls", page(SettingsKeys.CALLS), "block spam decline unknown numbers telemarketing contacts"),
        Entry("Allow Repeat Callers", "A second call within 5 min rings through", "Settings › Calls", page(SettingsKeys.CALLS), "urgent emergency again twice"),
        Entry("Show Rejected On Dash", "REJECTED on the dash's message screen", "Settings › Calls", page(SettingsKeys.CALLS), "rejected declined"),
        // Messages
        Entry("Message Alerts", "New WhatsApp and SMS on the dash", "Settings › Messages", page(SettingsKeys.MESSAGES), "sms text whatsapp chat notification envelope"),
        Entry("Skip Business SMS", "Keep bank, OTP and promo SMS off the dash", "Settings › Messages", page(SettingsKeys.MESSAGES), "otp bank spam promo ads offers government"),
        Entry("Auto-Reply While Riding", "Answers WhatsApp chats for you", "Settings › Messages", page(SettingsKeys.MESSAGES), "autoreply reply busy riding respond away"),
        // Appearance
        Entry("Theme", "System, Light, Dark or AMOLED", "Settings › Appearance", page(SettingsKeys.APPEARANCE), "dark mode light amoled black night"),
        Entry("Accent", "The app's colour", "Settings › Appearance", page(SettingsKeys.APPEARANCE), "color colour wallpaper aurora ember viridian ultraviolet solar"),
        // Connection
        Entry("Connect Automatically", "On ignition and after the phone restarts", "Settings", page(SettingsKeys.MENU), "auto connect bluetooth boot start"),
        Entry("Wake On Ignition", "Sleeps while parked, wakes at switch-on", "Settings", page(SettingsKeys.MENU), "battery sleep companion parked ignition"),
        Entry("Setup Checklist", "Permissions and battery, all in one place", "Settings", Target.Checklist, "permission bluetooth notification access location battery unrestricted optimization contacts caller name fix problem"),
        // Backup and updates
        Entry("Back Up Now", "Everything in one zip", "Settings › Backup & Restore", page(SettingsKeys.BACKUP), "backup save export drive"),
        Entry("Restore From Backup", "Bring everything back from a zip", "Settings › Backup & Restore", page(SettingsKeys.BACKUP), "restore import new phone"),
        Entry("Back Up After Every Trip", "A fresh backup each time a ride is saved", "Settings › Backup & Restore", page(SettingsKeys.BACKUP), "auto backup automatic"),
        Entry("Check For Updates", "Look for a new version", "Settings › Updates", page(SettingsKeys.UPDATES), "update upgrade new version download install"),
        Entry("Check On Launch", "Once a day, when the app opens", "Settings › Updates", page(SettingsKeys.UPDATES), "auto update"),
        // Troubleshooting
        Entry("Dash Test", "Check turn icons and text on your dash", "Settings › Troubleshooting", page(SettingsKeys.TROUBLESHOOTING), "test icons arrows display"),
        Entry("Traffic Side", "Left or right: roundabout and U-turn icons", "Settings › Troubleshooting", page(SettingsKeys.TROUBLESHOOTING), "left right hand drive roundabout uturn"),
        Entry("Log", "View or share what the app did", "Settings › Troubleshooting", page(SettingsKeys.TROUBLESHOOTING), "logs debug bug report problem"),
        // Bike tab
        Entry("Engine Warm-Up Temp", "°C the engine counts as warmed up", "Bike › Thermals", Target.Bike(BIKE_THERMALS), "warm warmed cold temperature temp engine"),
        Entry("Engine Heat Warning", "°C the engine counts as hot", "Bike › Thermals", Target.Bike(BIKE_THERMALS), "hot heat overheat temperature temp engine limit"),
        Entry("Warmed Up Alert On Dash", "WARMED UP on the dash once per ride", "Bike › Thermals", Target.Bike(BIKE_THERMALS), "warm temperature temp engine alert"),
        Entry("Overheat Alert On Dash", "ENGINE HOT on the dash", "Bike › Thermals", Target.Bike(BIKE_THERMALS), "hot heat overheat temperature temp engine alert"),
        Entry("Tank Size", "Litres your tank holds", "Bike › Fuel", Target.Bike(BIKE_FUEL), "fuel petrol tank litres capacity"),
        Entry("Reserve", "Litres left when the last bar blinks", "Bike › Fuel", Target.Bike(BIKE_FUEL), "fuel petrol reserve low last bar"),
        Entry("Ask At Refill", "Litres and amount after a refill", "Bike › Fuel", Target.Bike(BIKE_FUEL), "fuel petrol refill pump price cost amount"),
        Entry("Low Range Warning", "Warns on the Ride tab below some km", "Bike › Fuel", Target.Bike(BIKE_FUEL), "fuel petrol range low empty warning"),
        Entry("Mileage", "km/l, cost per km and range", "Bike › Fuel", Target.Bike(BIKE_FUEL), "fuel petrol economy kmpl average bars"),
        Entry("Service Reminders", "Chain, service and your own jobs", "Bike › Service", Target.Bike(BIKE_SERVICE), "maintenance chain lube oil service reminder"),
        Entry("Papers", "PUC, insurance and licence, with expiry reminders", "Bike › Service", Target.Bike(BIKE_SERVICE), "documents puc pollution insurance licence license dl rc expiry"),
        // Trips tab
        Entry("Record Trips", "Save each ride", "Trips", Target.Trips, "ride history log record"),
        Entry("Record Route", "Draw each ride on a map", "Trips", Target.Trips, "gps map route location track"),
        Entry("Trip Places", "Where each ride starts and ends", "Trips", Target.Trips, "places location start end address"),
    )

    /**
     * Entries matching [query]: every word typed must start a word of the
     * title, description or other words. Best matches first: those whose
     * title matches come before those found by meaning.
     */
    fun search(query: String): List<Entry> {
        val wanted = words(query)
        if (wanted.isEmpty()) return emptyList()
        return entries.mapNotNull { e ->
            val title = words(e.title)
            val all = title + words(e.detail) + words(e.words)
            if (!wanted.all { w -> all.any { it.startsWith(w) } }) return@mapNotNull null
            val inTitle = wanted.count { w -> title.any { it.startsWith(w) } }
            e to inTitle
        }.sortedByDescending { it.second }.map { it.first }
    }

    private fun words(s: String): List<String> =
        s.lowercase().split(Regex("[^a-z0-9°]+")).filter { it.isNotBlank() }
}

/** Settings page keys, shared with [SettingsScreen]. */
object SettingsKeys {
    const val MENU = "menu"
    const val RIDER = "rider"
    const val DASH = "dash"
    const val CALLS = "calls"
    const val MESSAGES = "messages"
    const val APPEARANCE = "appearance"
    const val UPDATES = "updates"
    const val BACKUP = "backup"
    const val TROUBLESHOOTING = "troubleshooting"
}
