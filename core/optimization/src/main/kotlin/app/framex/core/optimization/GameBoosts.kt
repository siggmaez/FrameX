package app.framex.core.optimization

import app.framex.core.common.OptAction

enum class BoostLevel { Off, Safe, Strong, Max }

/** Advice for settings INSIDE the game; FrameX cannot change those and never pretends to. */
enum class GameTip { FrameRateMax, HighFrameRateMode, GraphicsLow, ShadowsOff, ResolutionLow, RenderDistance }

/**
 * How a game is boosted, because games are limited by different things:
 * [PacingFirst] light on the graphics chip and capped by the game itself: lowering resolution only blurs it, so the
 * boost fixes frame pacing instead. [GpuHeavy] limited by the graphics chip: render resolution is the main lever.
 * [Sandbox] limited by both CPU and graphics (draw distance matters most). [Competitive] steadiness and ping matter most.
 */
enum class GameStrategy { PacingFirst, GpuHeavy, Sandbox, Competitive }

/**
 * @property knownCapFps the frame rate the game itself stops at, if known: no outside boost goes beyond it.
 * @property scales render resolution at the Strong and Max levels (absent = this game is not resolution-limited).
 * @property heavy runs hot for long sessions: Max also dims the screen to run cooler.
 * @property pingSensitive online game where lag matters: adds the Wi-Fi low-latency modes.
 */
data class GameBoostSpec(
    val id: String,
    val strategy: GameStrategy,
    val scales: Map<BoostLevel, Float>,
    val tips: List<GameTip>,
    val knownCapFps: Int? = null,
    val heavy: Boolean = false,
    val pingSensitive: Boolean = false,
)

/** What a boost level does for one game on this phone. Each part is a real system request, applied only if the phone supports it. */
data class BoostPlan(val level: BoostLevel, val extraActions: Set<OptAction>, val renderScale: Float?, val refreshHz: Int?, val spec: GameBoostSpec)

object GameBoosts {
    private val catalog: Map<String, GameBoostSpec> = buildMap {
        fun add(spec: GameBoostSpec, vararg packages: String) = packages.forEach { put(it, spec) }
        add(GameBoostSpec("brawlstars", GameStrategy.PacingFirst, emptyMap(),
            listOf(GameTip.HighFrameRateMode, GameTip.GraphicsLow), knownCapFps = 60, pingSensitive = true), "com.supercell.brawlstars")
        add(GameBoostSpec("clash", GameStrategy.PacingFirst, emptyMap(),
            listOf(GameTip.HighFrameRateMode, GameTip.GraphicsLow), knownCapFps = 60), "com.supercell.clashroyale", "com.supercell.clashofclans")
        add(GameBoostSpec("roblox", GameStrategy.Sandbox, mapOf(BoostLevel.Strong to 0.8f, BoostLevel.Max to 0.65f),
            listOf(GameTip.GraphicsLow, GameTip.ResolutionLow, GameTip.RenderDistance), knownCapFps = 60), "com.roblox.client")
        add(GameBoostSpec("pubg", GameStrategy.Competitive, mapOf(BoostLevel.Strong to 0.85f, BoostLevel.Max to 0.75f),
            listOf(GameTip.GraphicsLow, GameTip.FrameRateMax, GameTip.ShadowsOff), heavy = true, pingSensitive = true),
            "com.tencent.ig", "com.pubg.imobile", "com.pubg.krmobile", "com.vng.pubgmobile")
        add(GameBoostSpec("codm", GameStrategy.Competitive, mapOf(BoostLevel.Strong to 0.85f, BoostLevel.Max to 0.75f),
            listOf(GameTip.GraphicsLow, GameTip.FrameRateMax, GameTip.ShadowsOff), heavy = true, pingSensitive = true),
            "com.activision.callofduty.shooter", "com.garena.game.codm")
        add(GameBoostSpec("hoyo", GameStrategy.GpuHeavy, mapOf(BoostLevel.Strong to 0.8f, BoostLevel.Max to 0.7f),
            listOf(GameTip.FrameRateMax, GameTip.ResolutionLow, GameTip.ShadowsOff), knownCapFps = 60, heavy = true),
            "com.miHoYo.GenshinImpact", "com.HoYoverse.hkrpgoversea")
        add(GameBoostSpec("freefire", GameStrategy.Competitive, mapOf(BoostLevel.Strong to 0.9f, BoostLevel.Max to 0.85f),
            listOf(GameTip.GraphicsLow, GameTip.FrameRateMax), pingSensitive = true), "com.dts.freefireth", "com.dts.freefiremax")
        add(GameBoostSpec("mlbb", GameStrategy.Competitive, mapOf(BoostLevel.Strong to 0.9f, BoostLevel.Max to 0.85f),
            listOf(GameTip.HighFrameRateMode, GameTip.GraphicsLow, GameTip.ShadowsOff), pingSensitive = true), "com.mobile.legends")
        add(GameBoostSpec("minecraft", GameStrategy.Sandbox, mapOf(BoostLevel.Strong to 0.85f, BoostLevel.Max to 0.7f),
            listOf(GameTip.RenderDistance, GameTip.GraphicsLow, GameTip.ShadowsOff)), "com.mojang.minecraftpe")
        add(GameBoostSpec("standoff2", GameStrategy.Competitive, mapOf(BoostLevel.Strong to 0.85f, BoostLevel.Max to 0.75f),
            listOf(GameTip.GraphicsLow, GameTip.FrameRateMax, GameTip.ShadowsOff), heavy = true, pingSensitive = true), "com.axlebolt.standoff2")
    }

    /** Special boosts exist only for games FrameX has a tuned strategy for. */
    fun isSpecialized(pkg: String) = pkg in catalog
    fun specFor(pkg: String): GameBoostSpec? = catalog[pkg]

    /**
     * The refresh rate to lock. A game capped at N FPS runs evenly only on a screen rate that is a multiple of N
     * (60 FPS on a 90 Hz screen judders), so the highest multiple is chosen; with no known cap, the highest rate.
     */
    fun refreshFor(spec: GameBoostSpec, supportedHz: List<Int>): Int? {
        if (supportedHz.isEmpty()) return null
        val cap = spec.knownCapFps ?: return supportedHz.max()
        return supportedHz.filter { it % cap == 0 }.maxOrNull() ?: supportedHz.filter { it >= cap }.minOrNull() ?: supportedHz.max()
    }

    /**
     * Safe: lock a matching refresh rate (+ Wi-Fi low-latency modes for ping-sensitive games).
     * Strong: + fixed performance mode, and lower render resolution if this game is resolution-limited.
     * Max: + a lower resolution still, and for heavy games a dimmer screen so the phone runs cooler.
     * Null for games without a tuned strategy: no special boost there.
     */
    fun plan(pkg: String, level: BoostLevel, supportedHz: List<Int> = emptyList()): BoostPlan? {
        if (level == BoostLevel.Off) return null
        val spec = catalog[pkg] ?: return null
        val actions = linkedSetOf(OptAction.RefreshLock)
        if (spec.pingSensitive) actions += OptAction.WifiLowLatencyMode
        if (level.ordinal >= BoostLevel.Strong.ordinal) actions += OptAction.FixedPerformance
        val scale = spec.scales[level]?.takeIf { it < 1f }
        if (scale != null) actions += OptAction.RenderScale
        if (level == BoostLevel.Max && spec.heavy) actions += OptAction.ReduceBrightness
        return BoostPlan(level, actions, scale, refreshFor(spec, supportedHz), spec)
    }
}
