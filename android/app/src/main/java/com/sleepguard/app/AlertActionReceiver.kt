package com.sleepguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 「我醒了」按钮 → 停止告警循环 */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        WearAlert.stopAlert(ctx)
    }
}
