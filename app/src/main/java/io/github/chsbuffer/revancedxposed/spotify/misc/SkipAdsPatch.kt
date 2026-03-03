package io.github.chsbuffer.revancedxposed.spotify.misc

import android.os.Handler
import android.os.Looper
import app.revanced.extension.shared.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import java.lang.reflect.Constructor
import java.util.concurrent.atomic.AtomicReference

fun SpotifyHook.SkipAds() {
    val mainHandler = Handler(Looper.getMainLooper())

    // Holds the epj0 skip command dispatcher.
    // epj0.accept(awc0Instance) routes a SkipToNextTrack command through the player pipeline.
    val skipExecutorRef = AtomicReference<Any?>(null)

    // fwc0 (base class of all player commands) declares:
    //   Object a(...13 params...)  – visitor dispatch
    //   void   b(...12 params...)  – command execution  ← we want this one
    // fwc0.b(w5n0, fpp0, twi0, eei0, qej0, [epj0], svj0, e3p0, f1j0, dpp0, epp0, rri0)
    //                                         ^^^^^ index 5 is the skip executor
    // Using parameterCount == 12 (from fwc0 abstract signature) for reliable method lookup.
    fun findCommandBMethod(clazz: Class<*>) =
        clazz.declaredMethods.first { it.returnType == Void.TYPE && it.parameterCount == 12 }

    val captureExecutorHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.args.getOrNull(5)?.let { epj0 ->
                if (skipExecutorRef.compareAndSet(null, epj0)) {
                    Logger.printDebug { "SkipAds: captured skip executor via ${param.method.declaringClass.simpleName}" }
                }
            }
        }
    }

    // ── Source A: awc0.b() – user presses the skip button ────────────────────────
    val awc0Class = ::skipToNextTrackClassFingerprint.method.declaringClass
    XposedBridge.hookMethod(findCommandBMethod(awc0Class), captureExecutorHook)

    // ── Source B: bwc0.b() – Spotify internally skips on natural track end ───────
    // This fires on EVERY track transition without user interaction, so the executor
    // is captured immediately on first play, eliminating the "press skip once" requirement.
    val bwc0Class = ::skipToNextTrackWithCommandClassFingerprint.method.declaringClass
    XposedBridge.hookMethod(findCommandBMethod(bwc0Class), captureExecutorHook)

    // ── Step 2: find tut0 class (ad track model) ─────────────────────────────────
    // adTrackClassFingerprint finds tut0.a() which returns "spotify:ad:" + this.a
    val tut0Class = ::adTrackClassFingerprint.method.declaringClass

    // Hook tut0 constructors: fires when Spotify prepares an ad track for playback.
    // tut0 has two constructors:
    //   primary  (17 params, last = qtl)  – actual construction
    //   thunk    (18 params, last = int)  – Kotlin default-args dispatcher, calls primary
    // Only act on the primary to avoid triggering skip twice per ad.
    XposedBridge.hookAllConstructors(tut0Class, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            // Filter out the Kotlin default-args thunk (last parameter is primitive int)
            val paramTypes = (param.method as Constructor<*>).parameterTypes
            if (paramTypes.lastOrNull() == Int::class.javaPrimitiveType) return

            Logger.printDebug { "SkipAds: ad track created, scheduling skip" }

            mainHandler.postDelayed({
                val executor = skipExecutorRef.get()
                if (executor == null) {
                    // Executor not yet captured – this can only happen if the ad is the very
                    // first track ever played in this session (no natural transition yet).
                    Logger.printDebug { "SkipAds: skip executor not yet captured, cannot auto-skip" }
                    return@postDelayed
                }
                runCatching {
                    val awc0Instance = XposedHelpers.newInstance(awc0Class)
                    XposedHelpers.callMethod(executor, "accept", awc0Instance)
                    Logger.printDebug { "SkipAds: skip command dispatched successfully" }
                }.onFailure { e ->
                    Logger.printDebug { "SkipAds: skip dispatch failed: ${e.message}" }
                }
            }, 500L)
        }
    })
}
