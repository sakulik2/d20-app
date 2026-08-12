package xyz.sakulik.d20.app.testing

import android.os.Build
import androidx.test.runner.AndroidJUnitRunner

class D20TestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            super.onStart()
            return
        }
        uiAutomation.adoptShellPermissionIdentity(START_ACTIVITIES_FROM_BACKGROUND)
        try {
            super.onStart()
        } finally {
            uiAutomation.dropShellPermissionIdentity()
        }
    }

    private companion object {
        const val START_ACTIVITIES_FROM_BACKGROUND =
            "android.permission.START_ACTIVITIES_FROM_BACKGROUND"
    }
}
