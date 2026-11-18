package com.classgrid.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 下载完成后的安装跳板（第二阶段 → 第三阶段）。
 *
 * 为什么单独一个 Activity：通知点击要求一个显式入口，而「缺安装权限时先跳设置页」
 * 这段判断不该塞进 Service（Service 里 startActivity 会被后台启动限制坑）。
 * 本页不显示界面（manifest 用半透明主题），判断完立刻 finish。
 */
class UpdateInstallActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_VERSION_CODE = "version_code"

        fun intent(context: Context, versionCode: Int): Intent =
            Intent(context, UpdateInstallActivity::class.java)
                .putExtra(EXTRA_VERSION_CODE, versionCode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0)
        val file = UpdateDownloader.file(this, versionCode)
        if (!file.exists()) {
            Toast.makeText(this, R.string.update_file_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (!UpdateInstaller.canInstall(this)) {
            Toast.makeText(this, R.string.update_need_install_permission, Toast.LENGTH_LONG).show()
            UpdateInstaller.openInstallPermissionSettings(this)
            finish()
            return
        }
        val launched = UpdateInstaller.install(this, file)
        if (!launched) {
            Toast.makeText(this, R.string.update_install_failed, Toast.LENGTH_SHORT).show()
        } else {
            // 安装器已拉起，把「点击安装」通知撤掉
            UpdateService.clearDone(this)
        }
        finish()
    }
}
