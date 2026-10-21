package com.andrewevmiller.tvlauncher.home.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryMapperTest {
    // ApplicationInfo.CATEGORY_* values, repeated here because the production file must not import android.
    private val game = 0
    private val audio = 1
    private val video = 2
    private val image = 3
    private val social = 4
    private val news = 5
    private val maps = 6
    private val productivity = 7
    private val undefined = -1

    private fun assertAll(expected: AppCategory, androidCategory: Int, vararg packages: String) {
        for (pkg in packages) assertEquals(pkg, expected, categorize(pkg, androidCategory))
    }

    // ---- The plan's example packages (BUILD_PLAN 8.3) ----

    @Test
    fun planExamplePackages() {
        assertEquals(AppCategory.VIDEO, categorize("com.google.android.youtube.tv", undefined))
        assertEquals(AppCategory.VIDEO, categorize("com.netflix.ninja", undefined))
        assertEquals(AppCategory.VIDEO, categorize("com.plexapp.android", undefined))
        assertEquals(AppCategory.MUSIC, categorize("com.spotify.tv.android", undefined))
        assertEquals(AppCategory.MUSIC, categorize("com.aspiro.tidal", undefined))
        assertEquals(AppCategory.GAMES, categorize("com.limelight", undefined))
        assertEquals(AppCategory.GAMES, categorize("com.retroarch", undefined))
        assertEquals(AppCategory.TOOLS, categorize("com.alphainventor.filemanager", undefined))
        assertEquals(AppCategory.TOOLS, categorize("com.andrewevmiller.analyzer", undefined))
    }

    // ---- Hints, every one of them ----

    @Test
    fun videoHints() = assertAll(
        AppCategory.VIDEO, undefined,
        "com.google.android.youtube.tv", "com.netflix.ninja", "com.plexapp.android", "com.disney.disneyplus",
        "com.hulu.livingroomplus", "com.amazon.primevideo.x", "com.amazon.amazonvideo.livingroom",
        "com.hbo.hbonow", "com.tubitv", "tv.twitch.android.app", "com.crunchyroll.crunchyroid",
        "com.cbs.paramountplus", "com.peacocktv.peacockandroid", "org.xbmc.kodi", "org.jellyfin.androidtv",
        "com.emby.embytv", "org.videolan.vlc",
    )

    @Test
    fun musicHints() = assertAll(
        AppCategory.MUSIC, undefined,
        "com.spotify.tv.android", "com.aspiro.tidal", "deezer.android.tv", "com.pandora.android.atv",
        "com.soundcloud.android", "com.google.android.music", "com.amazon.music.tv",
        "com.google.android.youtube.tvmusic",
    )

    @Test
    fun gamesHints() = assertAll(
        AppCategory.GAMES, undefined,
        "com.limelight", "com.retroarch", "com.retroarch.aarch64", "com.valvesoftware.steamlink",
        "com.moonlight.stream", "com.google.android.play.games", "com.gameloft.android.tv",
    )

    @Test
    fun toolsHints() = assertAll(
        AppCategory.TOOLS, undefined,
        "com.alphainventor.filemanager", "com.google.android.apps.nbu.files", "com.cxinventor.fileexplorer",
        "com.mixplorer.filebrowser", "com.andrewevmiller.analyzer", "com.android.tv.settings",
        "com.android.terminal", "com.termux", "com.example.toolbox",
    )

    // ---- Hint matching is by package segment, not by substring ----

    @Test
    fun hintsDoNotMatchInsideSegments() {
        assertEquals(AppCategory.OTHER, categorize("com.example.profiles", undefined))
        assertEquals(AppCategory.OTHER, categorize("com.neighborhood.app", undefined))
        assertEquals(AppCategory.OTHER, categorize("com.example.complexity", undefined))
    }

    @Test
    fun hintsAreCaseInsensitive() {
        assertEquals(AppCategory.VIDEO, categorize("com.Netflix.Ninja", undefined))
        assertEquals(AppCategory.MUSIC, categorize("COM.SPOTIFY.TV", undefined))
    }

    @Test
    fun underscoreSeparatesSegmentsToo() {
        assertEquals(AppCategory.GAMES, categorize("com.example.my_game", undefined))
    }

    // ---- Precedence ----

    @Test
    fun musicBeatsVideoWhenBothHintsMatch() {
        assertEquals(AppCategory.MUSIC, categorize("com.google.android.apps.youtube.music", undefined))
    }

    @Test
    fun videoBeatsGamesWhenBothHintsMatch() {
        assertEquals(AppCategory.VIDEO, categorize("com.twitch.game.browser", undefined))
    }

    @Test
    fun gamesBeatsToolsWhenBothHintsMatch() {
        assertEquals(AppCategory.GAMES, categorize("com.example.game.tools", undefined))
    }

    @Test
    fun hintsWinOverTheAndroidCategory() {
        assertEquals(AppCategory.MUSIC, categorize("com.spotify.tv.android", game))
        assertEquals(AppCategory.VIDEO, categorize("com.netflix.ninja", audio))
        assertEquals(AppCategory.GAMES, categorize("com.limelight", video))
        assertEquals(AppCategory.TOOLS, categorize("com.alphainventor.filemanager", game))
    }

    // ---- Android category fallback, every value ----

    @Test
    fun androidCategoryMapsWhenNoHint() {
        val pkg = "com.example.unknown"
        assertEquals(AppCategory.GAMES, categorize(pkg, game))
        assertEquals(AppCategory.MUSIC, categorize(pkg, audio))
        assertEquals(AppCategory.VIDEO, categorize(pkg, video))
        assertEquals(AppCategory.TOOLS, categorize(pkg, productivity))
    }

    @Test
    fun otherAndroidCategoriesAreOther() {
        val pkg = "com.example.unknown"
        assertEquals(AppCategory.OTHER, categorize(pkg, image))
        assertEquals(AppCategory.OTHER, categorize(pkg, social))
        assertEquals(AppCategory.OTHER, categorize(pkg, news))
        assertEquals(AppCategory.OTHER, categorize(pkg, maps))
        assertEquals(AppCategory.OTHER, categorize(pkg, undefined))
    }

    @Test
    fun unknownAndOutOfRangeAndroidCategoriesAreOther() {
        assertEquals(AppCategory.OTHER, categorize("com.example.unknown", 99))
        assertEquals(AppCategory.OTHER, categorize("com.example.unknown", -7))
    }

    @Test
    fun emptyPackageNameFallsThroughToAndroidCategory() {
        assertEquals(AppCategory.OTHER, categorize("", undefined))
        assertEquals(AppCategory.GAMES, categorize("", game))
    }
}
