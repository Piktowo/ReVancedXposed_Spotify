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

    // Holds the epj0 skip command dispatcher, captured the first time the user presses skip.
    // epj0.accept(awc0Instance) routes a SkipToNextTrack command through the player pipeline.
    val skipExecutorRef = AtomicReference<Any?>(null)

    // ── Step 1: find awc0 class (SkipToNextTrack{}) ──────────────────────────────
    // skipToNextTrackClassFingerprint finds awc0.toString(); .declaringClass = awc0
    val awc0Class = ::skipToNextTrackClassFingerprint.method.declaringClass

    // awc0 extends abstract fwc0 which has exactly two methods:
    //   Object a(...13 params...)  – visitor dispatch
    //   void   b(...12 params...)  – command execution  ← we want this one
    // Find b() by: return void, not a JVM synthetic/standard method name
    val bMethod = awc0Class.declaredMethods.first { m ->
        m.returnType == Void.TYPE
            && m.name != "equals"
            && m.name != "hashCode"
            && m.name != "toString"
            && !m.name.startsWith("<")
    }

    // Hook awc0.b() to capture the skip executor (epj0Var = args[5])
    // fwc0.b(w5n0, fpp0, twi0, eei0, qej0, [epj0], svj0, e3p0, f1j0, dpp0, epp0, rri0)
    //                                         ^^^^^ index 5
    XposedBridge.hookMethod(bMethod, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.args.getOrNull(5)?.let { epj0 ->
                skipExecutorRef.set(epj0)
                Logger.printDebug { "SkipAds: captured skip executor ${epj0.javaClass.name}" }
            }
        }
    })

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
                    // User hasn't pressed skip yet this session; executor not yet captured.
                    // The ad will play normally until the user manually skips once,
                    // after which all subsequent ads will be auto-skipped.
                    Logger.printDebug { "SkipAds: skip executor not yet captured, cannot auto-skip" }
                    return@postDelayed
                }
                runCatching {
                    // Instantiate a fresh SkipToNextTrack command and dispatch it
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
