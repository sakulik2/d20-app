package xyz.sakulik.d20.app.ui.common

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.*
import android.util.Log

/**
 * 感官反馈控制器 (音效 + 触觉)
 * 采用单例模式，通过 Application Context 初始化
 */
class SensoryController private constructor(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    // 性能优化：创建单线程执行器处理 Binder 调用，避免阻塞主线程
    private val hapticExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(5)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    // 音频资源 ID 映射
    private val sounds = mutableMapOf<String, Int>()

    init {
        // 性能优化：将音效预加载移至后台线程，避免阻塞主线程 (Binder 延迟)
        Thread {
            loadSound(context, "dice_clatter", "dice_clatter")
            loadSound(context, "success_fanfare", "success_fanfare")
            loadSound(context, "fail_thud", "fail_thud")
            loadSound(context, "ui_click", "ui_click")
        }.start()
    }

    private fun loadSound(context: Context, key: String, resName: String) {
        val resId = context.resources.getIdentifier(resName, "raw", context.packageName)
        if (resId != 0) {
            sounds[key] = soundPool.load(context, resId, 1)
        } else {
            Log.w("SensoryController", "Sound resource not found: $resName")
        }
    }

    /**
     * 播放预加载的音效
     */
    fun playSound(key: String) {
        sounds[key]?.let { id ->
            soundPool.play(id, 1f, 1f, 1, 0, 1f)
        }
    }

    /**
     * 骰子落地点重击震动 (Land Impact Vibration)
     */
    fun hapticLandImpact() {
        stopVibration()
        safeVibrate {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(25, 200))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(25)
            }
        }
    }

    /**
     * 大成功：强力、有节奏的三连震
     */
    fun hapticCriticalSuccess() {
        stopVibration()
        safeVibrate {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(
                    longArrayOf(0, 100, 50, 100, 50, 200),
                    intArrayOf(0, 255, 0, 255, 0, 255),
                    -1
                )
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 100, 50, 100, 50, 200), -1)
            }
        }
        playSound("success_fanfare")
    }

    /**
     * 检定失败：沉重、长周期的单次钝震
     */
    fun hapticCheckFailure() {
        stopVibration()
        safeVibrate {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(400, 150))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(400)
            }
        }
        playSound("fail_thud")
    }

    /**
     * 战斗开始：强力、急促的双重脉冲
     */
    fun hapticCombatStart() {
        stopVibration()
        safeVibrate {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 150), intArrayOf(0, 255, 0, 255), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 150, 100, 150), -1)
            }
        }
    }

    /**
     * 重伤状态：连续、微弱的急促震动（模拟心跳或危险感）
     */
    fun hapticHeavyDamage() {
        safeVibrate {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 50, 150, 50, 150), intArrayOf(0, 100, 0, 100, 0), 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 50, 150, 50, 150), 0)
            }
        }
    }

    /**
     * 获得物品：有节奏的小型震动
     */
    fun hapticItemGain() {
        safeVibrate {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 50, 80), intArrayOf(0, 120, 0, 120), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 80, 50, 80), -1)
            }
        }
    }

    /**
     * 重度触感：用于重要操作（如掷骰、开启战斗）
     */
    fun hapticHeavyClick() {
        safeVibrate {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, 200))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        }
    }

    /**
     * 标准点击触感：用于普通 UI 交互（如切换主题、打开菜单）
     */
    fun hapticClick() {
        safeVibrate {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(30, 120))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(30)
            }
        }
    }

    /**
     * 轻微触感：用于普通的 UI 交互（如开关设备、切换装备）
     */
    fun hapticSoftTick() {
        safeVibrate {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(20, 80))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(20)
            }
        }
    }

    /**
     * 停止所有震动
     */
    fun stopVibration() {
        hapticExecutor.execute {
            try {
                vibrator.cancel()
            } catch (e: Exception) {
                Log.e("SensoryController", "Failed to cancel vibration", e)
            }
        }
    }

    private fun safeVibrate(block: () -> Unit) {
        hapticExecutor.execute {
            try {
                if (vibrator.hasVibrator()) {
                    block()
                }
            } catch (e: Exception) {
                Log.e("SensoryController", "Vibration failed", e)
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: SensoryController? = null

        fun getInstance(context: Context): SensoryController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SensoryController(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
