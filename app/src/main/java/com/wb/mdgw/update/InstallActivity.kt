package com.wb.mdgw.update

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 透明中转页：供「下载完成通知」点击后安装 APK。
 *
 * Android 8+ 首次安装需用户授予「安装未知应用」权限；未授权先跳系统设置，
 * 授权返回后再调起系统安装器，全程无自有界面，结束即 finish。
 */
class InstallActivity : ComponentActivity() {

    private val unknownSourceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            tryInstall()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (UpdateManager.canInstallApks(this)) {
            tryInstall()
        } else {
            val ok = runCatching {
                unknownSourceLauncher.launch(UpdateManager.unknownSourcesSettingIntent(this))
            }.isSuccess
            if (!ok) finish()
        }
    }

    private fun tryInstall() {
        if (UpdateManager.canInstallApks(this)) {
            val file = UpdateManager.apkFile(this)
            if (file.exists()) {
                runCatching { startActivity(UpdateManager.installIntent(this, file)) }
            }
        }
        finish()
    }
}
