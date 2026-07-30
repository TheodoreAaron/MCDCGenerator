package com.example.mcdc

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
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

        // 让系统状态栏颜色与 App 背景一致（消除顶部灰条），并按深浅色切换图标明暗。
        // 与 MaterialTheme 默认 colorScheme.background 对齐：浅色 0xFFFDF8F6 / 深色 0xFF1B1B1F。
        // 不同厂商 ROM 对状态栏/Insets 接口兼容性不一，用 try/catch 兜底：美化失败绝不影响主功能。
        try {
            val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            window.statusBarColor = if (isDark) 0xFF1B1B1F.toInt() else 0xFFFDF8F6.toInt()
            setLightStatusIcons(!isDark) // 浅色背景 -> 深色素图标，保证时间/电量清晰可读
        } catch (_: Throwable) {
            // 静默忽略：状态栏配色是纯视觉增强，失败不应导致启动崩溃
        }

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
     * 设置状态栏图标/文字的明暗：lightIcons=true 表示在浅色背景上使用深色素图标（更清晰）。
     * 分别兼容 Android R(11) 及以上（WindowInsetsController）与旧版本（systemUiVisibility）。
     */
    private fun setLightStatusIcons(lightIcons: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                if (lightIcons) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (lightIcons) {
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
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
