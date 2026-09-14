package com.wb.mdgw

import android.app.Application
import android.widget.Toast
import kotlin.system.exitProcess

/**
 * 应用入口：只做一件事——接管未捕获异常，写本地崩溃日志。
 *
 * 为何不接第三方上报：本应用承诺「文件不出设备」，公文 / 案卷内容高度敏感，
 * 任何外发行为都不可接受。改为把堆栈落到应用私有目录，用户可在
 * 「设置 → 崩溃日志」主动导出给开发者排查，或一键清除。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 1) 先尽力落盘（日志本身绝不能再抛异常）
            runCatching { CrashLogStore.record(this, thread, throwable) }
            // 2) 提示用户（Toast 在崩溃进程中未必显示，失败也无妨）
            runCatching {
                Toast.makeText(this, "应用异常已记录，可在「设置 → 崩溃日志」中导出", Toast.LENGTH_LONG).show()
            }
            // 3) 交还给系统默认处理，保证行为与原崩溃流程一致（弹出"已停止运行"）
            if (previous != null) {
                runCatching { previous.uncaughtException(thread, throwable) }
            } else {
                exitProcess(10)
            }
        }
    }
}
