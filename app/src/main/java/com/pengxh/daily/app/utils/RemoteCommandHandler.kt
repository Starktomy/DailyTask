package com.pengxh.daily.app.utils

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.ui.MainActivity
import com.pengxh.daily.app.utils.ApplicationEvent
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus

class RemoteCommandHandler(private val context: Context) {
    private val kTag = "RemoteCommandHandler"
    private val httpRequestManager by lazy { HttpRequestManager(context) }
    private val emailManager by lazy { EmailManager(context) }
    private val batteryManager by lazy { context.getSystemService(BatteryManager::class.java) }

    fun handleCommand(notice: String) {
        when {
            notice.contains("执行任务") -> {
                EventBus.getDefault().post(ApplicationEvent.StartDailyTask)
            }

            notice.contains("终止任务") -> {
                EventBus.getDefault().post(ApplicationEvent.StopDailyTask)
            }

            notice.contains("开启循环") -> {
                SaveKeyValues.putValue(Constant.TASK_AUTO_START_KEY, true)
                sendChannelMessage("循环任务状态通知", "循环任务状态已更新为：开启")
            }

            notice.contains("关闭循环") -> {
                SaveKeyValues.putValue(Constant.TASK_AUTO_START_KEY, false)
                sendChannelMessage("循环任务状态通知", "循环任务状态已更新为：关闭")
            }

            notice.contains("息屏") -> {
                EventBus.getDefault().post(ApplicationEvent.ShowMaskView)
            }

            notice.contains("亮屏") -> {
                EventBus.getDefault().post(ApplicationEvent.HideMaskView)
            }

            notice.contains("考勤记录") -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val notices = try {
                        DatabaseWrapper.loadCurrentDayNotice()
                    } catch (e: Exception) {
                        Log.e(kTag, "Load notices failed", e)
                        emptyList()
                    }

                    val record = buildString {
                        var index = 1
                        notices.filter {
                            it.noticeMessage.contains("考勤打卡")
                        }.forEach {
                            append("【第${index}次】${it.noticeMessage}，时间：${it.postTime}\r\n")
                            index++
                        }
                    }

                    withContext(Dispatchers.Main) {
                        sendChannelMessage("当天考勤记录通知", record)
                    }
                }
            }

            notice.contains("状态查询") -> {
                val type = SaveKeyValues.getValue(Constant.CHANNEL_TYPE_KEY, 0) as Int
                val content = buildString {
                    appendLine("任务状态：${if (MainActivity.isTaskStarted) "运行中" else "已停止"}")
                    appendLine("悬浮权限：${if (MainActivity.isCanDrawOverlay) "已获取" else "被拒绝"}")
                    appendLine("截图服务：${if (ProjectionSession.isStateActive()) "正常" else "断开"}")
                    append("消息渠道：${if (type == 0) "企业微信" else if (type == 1) "QQ邮箱" else "飞书"}")
                }
                sendChannelMessage("状态查询通知", content)
            }

            notice.contains("截屏") -> {
                if (ProjectionSession.isStateActive()) {
                    context.openApplication()
                } else {
                    sendChannelMessage("截屏状态通知", "截屏服务已断开，截屏失败")
                }
            }

            else -> {
                val key = SaveKeyValues.getValue(Constant.TASK_COMMAND_KEY, "打卡") as String
                if (notice.contains(key)) {
                    context.openApplication(true)
                }
            }
        }
    }

    private fun sendChannelMessage(title: String, content: String) {
        val type = SaveKeyValues.getValue(Constant.CHANNEL_TYPE_KEY, 0) as Int
        when (type) {
            0 -> {
                // 企业微信
                httpRequestManager.sendMessage(title, content)
            }

            1 -> {
                // QQ邮箱
                emailManager.sendEmail(title, content, false)
            }

            2 -> {
                // 飞书
                httpRequestManager.sendFeishuMessage(title, content)
            }

            else -> {
                Log.d(kTag, "sendChannelMessage: 消息渠道不支持")
            }
        }
    }
}
