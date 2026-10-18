package xyz.robinroy.appblock

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import xyz.robinroy.appblock.E2e.ctx
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android's Auto Backup keeps a user's rules across a new phone or a
 * reinstall. It's on by default; this keeps it from being switched off by
 * accident (the manifest's allowBackup).
 */
@RunWith(AndroidJUnit4::class)
class BackupTest {

    @Test
    fun autoBackupIsOn() {
        assertTrue("allowBackup is off in the manifest",
            ctx.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
    }
}
