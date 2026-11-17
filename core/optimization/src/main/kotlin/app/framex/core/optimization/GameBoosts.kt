package app.framex.core.optimization

import app.framex.core.common.OptAction

enum class BoostLevel { Off, Safe, Strong, Max }

/** Advice for settings INSIDE the game; FrameX cannot change those and never pretends to. */
enum class GameTip { FrameRateMax, HighFrameRateMode, GraphicsLow, ShadowsOff, ResolutionLow, RenderDistance }

/**
 * @property knownCapFps the frame rate the game itself stops at, if known: no outside boost goes beyond it.
 * @property scales render resolution used at the Strong and Max levels (1.0 = native).
 */
data class GameBoostSpec(
    val id: String,
    val scales: Map<BoostLevel, Float>,
    val tips: List<GameTip>,
    val knownCapFps: Int? = null,
)

/** What a boost level does for one game. Each part is a real system request, applied only if the phone supports it. */
data class BoostPlan(val level: BoostLevel, val extraActions: Set<OptAction>, val renderScale: Float?, val spec: GameBoostSpec)

object GameBoosts {
    val generic = GameBoostSpec("generic", mapOf(BoostLevel.Strong to 0.9f, BoostLevel.Max to 0.75f),
        listOf(GameTip.GraphicsLow, GameTip.ShadowsOff, GameTip.FrameRateMax))

    private val catalog: Map<String, GameBoostSpec> = buildMap {
        fun add(spec: GameBoostSpec, vararg packages: String) = packages.forEach { put(it, spec) }
        add(GameBoostSpec("brawlstars", mapOf(BoostLevel.Strong to 0.9f, BoostLevel.Max to 0.8f),
            listOf(GameTip.HighFrameRateMode, GameTip.GraphicsLow), knownCapFps = 60), "com.supercell.brawlstars")
        add(GameBoostSpec("clash", mapOf(BoostLevel.Strong to 0.9f, BoostLevel.Max to 0.85f),
            listOf(GameTip.HighFrameRateMode, GameTip.GraphicsLow), knownCapFps = 60), "com.supercell.clashroyale", "com.supercell.clashofclans")
        add(GameBoostSpec("roblox", mapOf(BoostLevel.Strong to 0.8f, BoostLevel.Max to 0.65f),
            listOf(GameTip.GraphicsLow, GameTip.ResolutionLow, GameTip.RenderDistance), knownCapFps = 60), "com.roblox.client")
        add(GameBoostSpec("pubg", mapOf(BoostLevel.Strong to 0.85f, BoostLevel.Max to 0.75f),
            listOf(GameTip.GraphicsLow, GameTip.FrameRateMax, GameTip.ShadowsOff)),
            "com.tencent.ig", "com.pubg.imobile", "com.pubg.krmobile", "com.vng.pubgmobile")
        add(GameBoostSpec("codm", mapOf(BoostLevel.Strong to 0.85f, BoostLevel.Max to 0.75f),
            listOf(GameTip.GraphicsLow, GameTip.FrameRateMax, GameTip.ShadowsOff)),
            "com.activision.callofduty.shooter", "com.garena.game.codm")
        add(GameBoostSpec("hoyo", mapOf(BoostLevel.Strong to 0.8f, BoostLevel.Max to 0.7f),
            listOf(GameTip.FrameRateMax, GameTip.ResolutionLow, GameTip.ShadowsOff), knownCapFps = 60),
            "com.miHoYo.GenshinImpact", "com.HoYoverse.hkrpgoversea")
        add(GameBoostSpec("freefire", mapOf(BoostLevel.Strong to 0.9f, BoostLevel.Max to 0.8f),
            listOf(GameTip.GraphicsLow, GameTip.FrameRateMax)), "com.dts.freefireth", "com.dts.freefiremax")
        add(GameBoostSpec("mlbb", mapOf(BoostLevel.Strong to 0.9f, BoostLevel.Max to 0.8f),
            listOf(GameTip.HighFrameRateMode, GameTip.GraphicsLow, GameTip.ShadowsOff)), "com.mobile.legends")
        add(GameBoostSpec("minecraft", mapOf(BoostLevel.Strong to 0.85f, BoostLevel.Max to 0.7f),
            listOf(GameTip.RenderDistance, GameTip.GraphicsLow, GameTip.ShadowsOff)), "com.mojang.minecraftpe")
        add(GameBoostSpec("standoff2", mapOf(BoostLevel.Strong to 0.85f, BoostLevel.Max to 0.75f),
            listOf(GameTip.GraphicsLow, GameTip.FrameRateMax, GameTip.ShadowsOff)), "com.axlebolt.standoff2")
    }

    fun isSpecialized(pkg: String) = pkg in catalog
    fun specFor(pkg: String): GameBoostSpec = catalog[pkg] ?: generic

    /**
     * Safe: lock the screen to its highest refresh rate.
     * Strong: + ask the system for fixed performance mode and lower the render resolution a little.
     * Max: + a lower render resolution still.
     */
    fun plan(pkg: String, level: BoostLevel): BoostPlan? {
        if (level == BoostLevel.Off) return null
        val spec = specFor(pkg)
        val actions = linkedSetOf(OptAction.RefreshLock)
        if (level.ordinal >= BoostLevel.Strong.ordinal) actions += OptAction.FixedPerformance
        val scale = spec.scales[level]?.takeIf { it < 1f }
        if (scale != null) actions += OptAction.RenderScale
        return BoostPlan(level, actions, scale, spec)
    }
}
