package io.github.whyoolw.monopod

import android.app.Application
import io.github.whyoolw.monopod.data.LibraryRepository
import io.github.whyoolw.monopod.data.StatsRepository
import io.github.whyoolw.monopod.spotify.Spotify
import io.github.whyoolw.monopod.yandex.YandexMusic
import io.github.whyoolw.monopod.ui.menu.CoverCache
import java.io.File
import kotlinx.coroutines.MainScope

class Monopod : Application() {
    /** App-wide, so a scan keeps going while the activity is recreated. */
    val library by lazy { LibraryRepository(this, MainScope()) }
    /** Play counts and ratings; the playback service writes them, the menus read them. */
    val stats by lazy { StatsRepository(this, MainScope()) }
    /** App-wide too: the App Remote connection should survive a rotation. */
    val spotify by lazy { Spotify(this) }
    val yandex by lazy { YandexMusic(this) }

    override fun onCreate() {
        super.onCreate()
        // Keep the last crash, readable in Developer Mode, since a phone shows no log
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                File(filesDir, CRASH_FILE).writeText("${java.util.Date()}\n${android.util.Log.getStackTraceString(e)}")
            }
            previous?.uncaughtException(thread, e)
        }
        CoverCache.remoteDir = File(cacheDir, "yandex/covers")
        io.github.whyoolw.monopod.ui.theme.CustomBodies.init(this)
        // Start reading play counts now, so smart playlists have them by the time anyone looks
        stats
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
