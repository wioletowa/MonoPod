package io.github.whyoolw.monopod.spotify

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.ContentApi
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.ImageUri
import com.spotify.protocol.types.ListItem
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Spotify as a second music source: the Web API lists the playlists, the installed Spotify app
 * plays them (App Remote, needs Premium), and the iPod screen mirrors what it plays.
 */
class Spotify(private val context: Context) {
    val auth = SpotifyAuth(context)
    val api = SpotifyApi(auth)
    /** Playlist covers and the drawn covers of the home lists. */
    val coverDir = File(context.cacheDir, "spotify/covers")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var remote: SpotifyAppRemote? = null
    private var connecting = false
    /** What to run once App Remote is connected (the play that asked for the connection). */
    private var pending: ((SpotifyAppRemote) -> Unit)? = null

    var signedIn by mutableStateOf(auth.signedIn)
        private set
    /** Whether this install knows which Spotify app (Client ID) to sign in through. */
    var hasClientId by mutableStateOf(auth.hasClientId)
        private set

    fun setup() {
        context.startActivity(Intent(context, SpotifySetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Another Spotify app: the old sign-in and connection do not carry over. */
    fun setClientId(id: String) {
        stopMirroring()
        remote = null
        auth.setClientId(id)
        signedIn = false
        hasClientId = auth.hasClientId
    }
    /** Set while Spotify, not the local library, is what plays: the wheel buttons go to it. */
    var active by mutableStateOf(false)
        private set
    var connected by mutableStateOf(false)
        private set
    /** Why the last connection failed, shown on the Now Playing screen. */
    var error by mutableStateOf<String?>(null)
        private set

    var title by mutableStateOf("")
        private set
    var artist by mutableStateOf("")
        private set
    var album by mutableStateOf("")
        private set
    var trackUri by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    /** The Spotify app's own shuffle and repeat (Player.REPEAT_MODE_* values), shown and toggled from the iPod. */
    var shuffle by mutableStateOf(false)
        private set
    var repeatMode by mutableIntStateOf(0)
        private set
    /** The current cover saved as a file, so the iPod cover view and the Material colors can use it. */
    var coverPath by mutableStateOf<String?>(null)
        private set
    private var positionAt = 0L
    private var positionAtUptime = 0L
    private var coverUri: String? = null

    /** Spotify only reports position on changes; between them it runs with the clock. */
    val positionMs: Long
        get() = if (isPlaying) positionAt + SystemClock.uptimeMillis() - positionAtUptime else positionAt

    val installed: Boolean get() = SpotifyAppRemote.isSpotifyInstalled(context)

    fun login() {
        context.startActivity(Intent(Intent.ACTION_VIEW, auth.loginUrl()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The browser came back with [uri]; true if it was a Spotify sign-in. */
    fun handleRedirect(uri: Uri?): Boolean {
        if (uri?.scheme != "monopod" || uri.host != "spotify-callback") return false
        scope.launch {
            withContext(Dispatchers.IO) { auth.finish(uri) }
            signedIn = auth.signedIn
        }
        return true
    }

    fun signOut() {
        auth.signOut()
        signedIn = false
        stopMirroring()
    }

    /** Switched off: signed out and every downloaded cover gone, as if never used. */
    fun forgetEverything() {
        deactivate()
        signOut()
        auth.setClientId("")
        hasClientId = auth.hasClientId
        coverPath = null
        File(context.cacheDir, "spotify").deleteRecursively()
    }

    /** Plays a whole playlist, album or artist from the start, for those the Web API cannot list. */
    fun playUri(uri: String) = withRemote { it.playerApi.play(uri) }

    /** The Spotify home page, through the Spotify app: its sections and what is in them. */
    suspend fun homeSections(): List<ListItem> = call { it.contentApi.getRecommendedContentItems(ContentApi.ContentType.DEFAULT) }.items.toList()

    suspend fun children(item: ListItem, count: Int = 30): List<ListItem> = call { it.contentApi.getChildrenOfItem(item, count, 0) }.items.toList()

    /** A picture from the Spotify app, saved as a file the cover views can read. */
    suspend fun saveImage(image: ImageUri, to: File): Boolean {
        if (to.exists()) return true
        val bitmap = runCatching { call { it.imagesApi.getImage(image, Image.Dimension.MEDIUM) } }.getOrNull() ?: return false
        withContext(Dispatchers.IO) {
            to.parentFile?.mkdirs()
            to.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        return true
    }

    /** One App Remote call as a suspend function; connects first (without taking over the wheel). */
    private suspend fun <T> call(request: (SpotifyAppRemote) -> CallResult<T>): T = suspendCancellableCoroutine { cont ->
        browse(
            onReady = { r ->
                request(r).setResultCallback { if (cont.isActive) cont.resume(it) }
                    .setErrorCallback { if (cont.isActive) cont.resumeWithException(it) }
            },
            onFail = { if (cont.isActive) cont.resumeWithException(it) },
        )
    }

    private val waiting = mutableListOf<Pair<(SpotifyAppRemote) -> Unit, (Throwable) -> Unit>>()

    private fun browse(onReady: (SpotifyAppRemote) -> Unit, onFail: (Throwable) -> Unit) {
        val r = remote
        if (r != null && r.isConnected) return onReady(r)
        if (!installed) return onFail(IllegalStateException(ERROR_NOT_INSTALLED))
        waiting += onReady to onFail
        connect()
    }

    /** Plays [contextUri] (a playlist, the liked songs) from song [index]. */
    /**
     * Plays [contextUri] (a playlist, album, the liked songs) from song [index] and on in the
     * list's order, through the Web API. Spotify's own App Remote jump only works inside a list
     * that already plays, which made songs start from the top or wander off.
     */
    fun playContext(contextUri: String, index: Int, trackUri: String? = null, fallback: (() -> Unit)? = null) = withRemote { r ->
        scope.launch {
            suspend fun start() = withContext(Dispatchers.IO) { runCatching { api.startPlayback(contextUri, index, trackUri) } }
            var result = start()
            if ((result.exceptionOrNull() as? SpotifyApi.Failure)?.code == 404) {
                // No active device yet: wake the phone's Spotify with the list, then ask again
                r.playerApi.play(contextUri)
                kotlinx.coroutines.delay(1500)
                result = start()
            }
            if (result.isFailure) {
                // Older sign-in without the playback permission: the App Remote way
                r.playerApi.play(contextUri).setResultCallback {
                    r.playerApi.skipToIndex(contextUri, index).setErrorCallback { fallback?.invoke() }
                }
            }
        }
    }

    /** A single song (nothing to go on with): no queueing, which would stick in Spotify's own queue. */
    fun playTracks(uris: List<String>, index: Int) = withRemote { r -> r.playerApi.play(uris[index]) }

    /** Whether the playing song is in the user's Liked Songs; null while unknown. */
    var liked by mutableStateOf<Boolean?>(null)
        private set

    fun toggleLike() = withRemote { r ->
        val uri = trackUri ?: return@withRemote
        val now = liked ?: return@withRemote
        val call = if (now) r.userApi.removeFromLibrary(uri) else r.userApi.addToLibrary(uri)
        call.setResultCallback { if (trackUri == uri) liked = !now }
    }

    fun playPause() = withRemote { if (isPlaying) it.playerApi.pause() else it.playerApi.resume() }
    fun toggleShuffle() = withRemote { it.playerApi.setShuffle(!shuffle) }
    // Spotify's repeat values (0 off, 1 one, 2 all) match Player.REPEAT_MODE_*; same order as the local player
    fun cycleRepeat() = withRemote { it.playerApi.setRepeat(when (repeatMode) { 0 -> 1; 1 -> 2; else -> 0 }) }
    fun next() = withRemote { it.playerApi.skipNext() }
    fun previous() = withRemote { it.playerApi.skipPrevious() }
    fun pause() { remote?.takeIf { it.isConnected }?.playerApi?.pause() }

    /**
     * Spotify reports the position only now and then; each wheel click moves our own position
     * right away, so quick clicks add up instead of all starting from a stale spot.
     */
    fun seekBy(deltaMs: Long) = withRemote {
        if (durationMs <= 0) return@withRemote
        val target = (positionMs + deltaMs).coerceIn(0L, durationMs - 500)
        positionAt = target
        positionAtUptime = SystemClock.uptimeMillis()
        seekingUntil = positionAtUptime + SEEK_SETTLE_MS
        it.playerApi.seekTo(target)
    }

    /** While clicks are still coming, late player reports (from before the seek) are ignored. */
    private var seekingUntil = 0L

    /** The local library took over: stop routing the wheel here (Spotify itself is paused by the caller). */
    fun deactivate() {
        if (active) pause()
        active = false
    }

    private fun withRemote(block: (SpotifyAppRemote) -> Unit) {
        active = true
        val r = remote
        if (r != null && r.isConnected) return block(r)
        pending = block
        connect()
    }

    private fun connect() {
        if (connecting) return
        if (!installed) {
            error = ERROR_NOT_INSTALLED
            return
        }
        connecting = true
        error = null
        val params = ConnectionParams.Builder(auth.clientId)
            .setRedirectUri(SpotifyAuth.REDIRECT_URI)
            .showAuthView(true)
            .build()
        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(r: SpotifyAppRemote) {
                connecting = false
                remote = r
                connected = true
                r.playerApi.subscribeToPlayerState().setEventCallback(::mirror)
                pending?.invoke(r)
                pending = null
                waiting.toList().also { waiting.clear() }.forEach { it.first(r) }
            }

            override fun onFailure(t: Throwable) {
                connecting = false
                connected = false
                remote = null
                pending = null
                waiting.toList().also { waiting.clear() }.forEach { it.second(t) }
                error = t.javaClass.simpleName.removeSuffix("Exception").ifEmpty { "Error" }
            }
        })
    }

    private fun mirror(state: PlayerState) {
        val track = state.track
        isPlaying = !state.isPaused
        if (SystemClock.uptimeMillis() >= seekingUntil) {
            positionAt = state.playbackPosition
            positionAtUptime = SystemClock.uptimeMillis()
        }
        state.playbackOptions?.let {
            shuffle = it.isShuffling
            repeatMode = it.repeatMode
        }
        if (track == null) return
        title = track.name.orEmpty()
        artist = track.artist?.name.orEmpty()
        album = track.album?.name.orEmpty()
        if (trackUri != track.uri) {
            liked = null
            val uri = track.uri
            remote?.userApi?.getLibraryState(uri)?.setResultCallback { if (trackUri == uri) liked = it.isAdded }
        }
        trackUri = track.uri
        durationMs = track.duration
        val image = track.imageUri
        if (image != null && image.raw != coverUri) {
            coverUri = image.raw
            remote?.imagesApi?.getImage(image, Image.Dimension.LARGE)?.setResultCallback { saveCover(image.raw, it) }
        }
    }

    /** One file per cover, so a path change means a new picture to everything that caches by path. */
    private fun saveCover(key: String?, bitmap: Bitmap) = scope.launch {
        val dir = File(context.cacheDir, "spotify").apply { mkdirs() }
        val file = File(dir, "${key.hashCode().toUInt()}.jpg")
        withContext(Dispatchers.IO) {
            if (!file.exists()) {
                // Keep the folder small: only the last few covers
                dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(8)?.forEach { it.delete() }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
        }
        if (key == coverUri) coverPath = file.path
    }

    private fun stopMirroring() {
        remote?.let(SpotifyAppRemote::disconnect)
        remote = null
        connected = false
        active = false
    }

    companion object {
        const val ERROR_NOT_INSTALLED = "not-installed"
        /** How long after a seek click Spotify's position reports are not trusted. */
        private const val SEEK_SETTLE_MS = 700L
    }
}
