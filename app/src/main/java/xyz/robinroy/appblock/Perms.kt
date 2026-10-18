package xyz.robinroy.appblock

import android.app.AppOpsManager
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

// Live state of each Storage.Requirement, shared by the home screen (setup
// checklist, paused note) and the About sheet, plus the optional Usage access.

/** True when AppBlock's accessibility service is switched on in Settings > Accessibility. */
fun accessibilityOn(ctx: Context): Boolean = Storage.serviceEnabled(
    Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
    ctx.packageName, BlockerService::class.java.name)

/** True when the user has granted "Usage access" (a special app op with its own
 *  Settings page — there is no runtime prompt for it). Optional: it only
 *  feeds the screen-time numbers on the home list and in the picker. */
fun usageAccessGranted(ctx: Context): Boolean {
    @Suppress("DEPRECATION")
    return ctx.getSystemService(AppOpsManager::class.java).checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
    ) == AppOpsManager.MODE_ALLOWED
}

fun batteryExempt(ctx: Context): Boolean =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

/** The `when` is exhaustive: a new Storage.Requirement won't compile until it's checked here. */
fun requirementMet(ctx: Context, req: Storage.Requirement): Boolean = when (req) {
    Storage.Requirement.ACCESSIBILITY -> accessibilityOn(ctx)
    Storage.Requirement.BATTERY -> batteryExempt(ctx)
}

fun missingRequirements(ctx: Context): List<Storage.Requirement> =
    Storage.Requirement.entries.filter { !requirementMet(ctx, it) }

fun allRequirementsMet(ctx: Context): Boolean = missingRequirements(ctx).isEmpty()

/** "package:xyz.robinroy.appblock", the form the per-app Settings pages take. */
fun packageUri(ctx: Context): Uri = Uri.parse("package:${ctx.packageName}")
