package com.yuk.fuckMiuiThemeManager

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 日志读取器：跟随 LSPosed 落盘日志，把本模块的 XposedBridge 输出喂给界面。
 *
 * 为什么不是读 logcat：Android 11 之后应用只能读自己的 logcat，主题管理器的
 * 日志根本读不到；而 XposedBridge.log 会被 LSPosed 统一写进
 * /data/adb/lspd/log/modules_*.log，用 root 直接读文件最可靠。
 */
class LogReader(private val act: LogActivity) : Runnable {

    /** 置 true 表示停止跟随 */
    @Volatile
    var stop = false

    /** 当前跟随的子进程，停止时需要销毁 */
    var proc: Process? = null
        private set

    override fun run() {
        try {
            val process = startLogcat()
            proc = process
            LogHelper.d("FTM_READER_START")

            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                while (!stop) {
                    val line = reader.readLine() ?: break
                    if (!isModuleLine(line)) continue

                    val text = trimLine(line)
                    if (act.isLevelEnabled(levelOf(text))) {
                        act.pending = text
                        act.runOnUiThread(act)
                    }
                }
            }
        } catch (t: Throwable) {
            LogHelper.ex(t)
        }
    }

    /** 优先 root 直接读 LSPosed 日志；拿不到 root 时退回读本进程 logcat */
    private fun startLogcat(): Process = try {
        Log.d(TAG, "FTM_SU_TRY: attempt su logcat")
        Runtime.getRuntime()
            .exec(arrayOf("su", "-c", "tail -n 60 -f $LSPD_LOG"))
            .also { Log.d(TAG, "FTM_SU_OK: su logcat started") }
    } catch (t: Throwable) {
        LogHelper.ex(t)
        Log.d(TAG, "FTM_SU_FAIL: su denied, fallback plain logcat")
        Runtime.getRuntime().exec(arrayOf("logcat", "-v", "threadtime", "-s", "$TAG:D"))
    }

    /**
     * 粗略判定日志等级：XposedBridge 通道里的行统一是 I 级，
     * 只能按内容判断是否是异常堆栈。
     */
    fun levelOf(line: String): Char =
        if (line.contains("xception") || line.contains("rror")) 'E' else 'I'

    companion object {

        private const val TAG = "FuckThemeManager"

        /** LSPosed 落盘日志路径（通配符，交给 shell 展开） */
        const val LSPD_LOG = "/data/adb/lspd/log/modules_*.log"

        /** LSPosed 写入的模块标识前缀，用于把系统/其它模块的日志全部过滤掉 */
        private const val MODULE_MARK = "[com.yuk.fuckMiuiThemeManager,XposedBridge"

        /** 只保留本模块的 XposedBridge 日志 */
        fun isModuleLine(line: String): Boolean = line.contains(MODULE_MARK)

        /**
         * 把 LSPosed 的长前缀裁掉，只留「时间 + 正文」：
         *
         * 输入：`[ 2026-09-14T04:36:13.441  10283:10283:10283 I/LSPosedFramework ] (宿主)[com.yuk...XposedBridge...] FTM: xxx`
         * 输出：`2026-09-14T04:36:13.441 FTM: xxx`
         *
         * 解析不出前缀时原样返回，保证不丢日志。
         */
        fun trimLine(line: String): String {
            val mark = line.indexOf(MODULE_MARK)
            val body = if (mark >= 0) {
                val close = line.indexOf(']', mark)
                if (close >= 0) line.substring(close + 1) else line.substring(mark)
            } else {
                line
            }

            // 时间戳：行首 '[' 之后的第一个 token
            val stamp = if (line.startsWith("[")) {
                line.substring(1).trimStart().substringBefore(' ')
            } else {
                ""
            }

            val msg = body.trimStart()
            return if (stamp.isNotEmpty() && stamp != line) "$stamp $msg" else msg
        }
    }
}
