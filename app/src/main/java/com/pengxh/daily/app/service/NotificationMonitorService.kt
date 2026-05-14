package com.pengxh.daily.app.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.NotificationBean
import com.pengxh.daily.app.ui.MainActivity
import com.pengxh.daily.app.utils.ApplicationEvent
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.HttpRequestManager
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.extensions.timestampToCompleteDate
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus

/**
 * @description: 状态栏监听服务
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/25 23:17
 */
class NotificationMonitorService : NotificationListenerService() {

    private val kTag = "MonitorService"
    private val remoteCommandHandler by lazy { RemoteCommandHandler(this) }
    private val auxiliaryApp = arrayOf(Constant.WECHAT, Constant.QQ, Constant.TIM, Constant.ZFB)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenerConnected = false

    /**
     * 有可用的并且和通知管理器连接成功时回调
     */
    override fun onListenerConnected() {
        listenerConnected = true
        EventBus.getDefault().post(ApplicationEvent.ListenerConnected)
    }

    /**
     * 当有新通知到来时会回调
     */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val pkg = sbn.packageName
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val notice = extras.getString(Notification.EXTRA_TEXT)
            ?: extras.getString(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString("\n")
            ?: extras.getString(Notification.EXTRA_SUMMARY_TEXT)

        if (notice.isNullOrBlank()) {
            return
        }

        val targetApp = Constant.getTargetApp()

        // 保存指定包名的通知，其他的一律不保存
        saveTargetNotice(pkg, targetApp, title, notice)

        // 目标应用打卡通知
        val resultSource = SaveKeyValues.getValue(Constant.RESULT_SOURCE_KEY, 0) as Int
        if (resultSource == 0) {
            if (pkg == targetApp && notice.contains("成功")) {
                EventBus.getDefault().post(ApplicationEvent.GoBackMainActivity)
                "即将发送通知邮件，请注意查收".show(this)
                val messageTitle =
                    SaveKeyValues.getValue(Constant.MESSAGE_TITLE_KEY, "打卡结果通知") as String
                // 此处仍保留直接调用，或也可以通过 handler 发送，但 handler 主要是处理指令。
                // 为了统一，我们可以给 RemoteCommandHandler 增加一个发送普通消息的方法
                remoteCommandHandler.handleCommand(notice) // 尝试解析指令
            }
        }

        // 其他消息指令
        if (pkg in auxiliaryApp) {
            remoteCommandHandler.handleCommand(notice)
        }
    }

    private fun saveTargetNotice(pkg: String, targetApp: String, title: String, notice: String) {
        if (pkg != targetApp && pkg !in auxiliaryApp) return

        NotificationBean().apply {
            packageName = pkg
            noticeTitle = title
            noticeMessage = notice
            postTime = System.currentTimeMillis().timestampToCompleteDate()
        }.also {
            serviceScope.launch {
                try {
                    DatabaseWrapper.insertNotice(it)
                } catch (e: Exception) {
                    Log.e(kTag, "Insert notice failed", e)
                }
            }
        }
    }

    /**
     * 当有通知移除时会回调
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    override fun onListenerDisconnected() {
        listenerConnected = false
        EventBus.getDefault().post(ApplicationEvent.ListenerDisconnected)
        // 主动请求系统重新绑定监听服务
        requestRebind(ComponentName(this, NotificationMonitorService::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}