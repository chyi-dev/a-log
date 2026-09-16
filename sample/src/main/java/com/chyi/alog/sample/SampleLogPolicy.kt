package com.chyi.alog.sample

import com.chyi.alog.ALogDefaults

/** Sample 启动与按钮是否允许注入 AndroidPrinter。Release 一律关闭。 */
object SampleLogPolicy {
    fun consoleOnLaunch(): Boolean = ALogDefaults.includeAndroidPrinter(BuildConfig.DEBUG)

    fun allowConsoleToggle(): Boolean = BuildConfig.DEBUG

    fun enableAndroidPrinter(consoleRequested: Boolean): Boolean =
        consoleRequested && ALogDefaults.includeAndroidPrinter(BuildConfig.DEBUG)
}
