package com.example.mcdc

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.mcdc.ui.McdcApp
import com.example.mcdc.ui.theme.MCDCTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 让渲染匹配设备原生刷新率（例如 120Hz 手机应跑满 120Hz，而非被限制到 60Hz）
        enableHighRefreshRate()
        setContent {
            MCDCTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    McdcApp()
                }
            }
        }
    }

    /**
     * 自适应设备帧率：选取「当前分辨率下刷新率最高」的显示模式并应用到窗口。
     *
     * 通过 [WindowManager.LayoutParams.preferredDisplayModeId]（API 23+）请求设备支持的最高
     * 刷新率模式（优先匹配当前分辨率，避免切换分辨率）。Compose 的滚动/动画默认跟随窗口刷新率，
     * 因此只要窗口运行在 120Hz 模式，滑动即可达到 120fps。
     */
    private fun enableHighRefreshRate() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay
        } ?: return
        val modes = display.supportedModes
        if (modes.isEmpty()) return

        val cur = display.getMode()
        // 优先选「与当前分辨率相同」且刷新率最高的模式，避免意外改变分辨率
        val best = modes.maxWithOrNull(
            compareBy(
                { cur != null && it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight },
                { it.refreshRate }
            )
        ) ?: return

        val params = window.attributes
        params.preferredDisplayModeId = best.modeId
        window.attributes = params
    }
}
