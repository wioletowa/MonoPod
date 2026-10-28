package io.github.whyoolw.monopod.data

import java.text.Collator

data class Track(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val genre: String?,
    val trackNo: Int?,
    val discNo: Int?,
    val year: Int?,
    val durationMs: Long,
    val albumKey: String,
    /** File modification time, used as "added to the library". */
    val addedAt: Long = 0L,
    /** The album key it was scanned with, when merged into another album. */
    val originalAlbumKey: String? = null,
)

data class Album(
    val key: String,
    val title: String?,
    val artist: String?,
    val year: Int?,
    /** In play order: disc, track number, title. */
    val tracks: List<Track>,
    val coverPath: String?,
)

data class Playlist(val name: String, val tracks: List<Track>)

/**
 * The whole library in memory, sorted the way the iPod sorts: case-insensitive, letters
 * compared by the system locale, a leading "The " ignored.
 */
class LibrarySnapshot(
    tracks: List<Track>,
    playlists: List<Playlist>,
    /** Song URI → .lrc file URI. */
    private val lyricsFiles: Map<String, String>,
    coverPath: (String) -> String?,
    /** Merge copies of one album split by how its artists were written (Developer Mode). */
    mergeAlbums: Boolean = false,
) {
    private val tracks: List<Track> = if (mergeAlbums) mergeSplitAlbums(tracks) else tracks
    private val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
    private val byName = Comparator<String?> { a, b -> collator.compare(sortKey(a), sortKey(b)) }

    val songs: List<Track> = this.tracks.sortedWith(compareBy(byName) { it.title })

    val albums: List<Album> = this.tracks.groupBy { it.albumKey }.map { (key, list) ->
        val first = list.first()
        Album(
            key = key,
            title = first.album,
            artist = if (mergeAlbums) sharedArtist(list) ?: first.albumArtist ?: first.artist else first.albumArtist ?: first.artist,
            year = list.firstNotNullOfOrNull { it.year },
            tracks = list.sortedWith(
                compareBy<Track>({ it.discNo ?: 1 }, { it.trackNo ?: Int.MAX_VALUE }).thenBy(byName) { it.title },
            ),
            coverPath = coverPath(key) ?: list.firstNotNullOfOrNull { t -> t.originalAlbumKey?.let(coverPath) },
        )
    }.sortedWith(compareBy(byName) { it.title })

    /** Track artists, like the iPod's Artists menu. */
    val artists: List<String?> = this.tracks.map { it.artist }.distinct().sortedWith(byName)

    val genres: List<String?> = this.tracks.map { it.genre }.distinct().sortedWith(byName)

    val playlists: List<Playlist> = playlists.sortedWith(compareBy(byName) { it.name })

    val isEmpty: Boolean get() = songs.isEmpty()

    private val covers = albums.associate { it.key to it.coverPath }

    fun coverPath(albumKey: String): String? = covers[albumKey]

    fun lyricsFile(trackUri: String): String? = lyricsFiles[trackUri]

    private val albumOfTrack = this.tracks.associate { it.uri to it.albumKey }

    /** Current cover of a song's album; the queue may hold an older one, or none. */
    fun coverForTrack(trackUri: String): String? = albumOfTrack[trackUri]?.let(::coverPath)

    fun albumsBy(artist: String?): List<Album> = albums.filter { a -> a.tracks.any { it.artist == artist } }

    fun songsBy(artist: String?): List<Track> =
        albumsBy(artist).flatMap { a -> a.tracks.filter { it.artist == artist } }

    fun artistsIn(genre: String?): List<String?> =
        artists.filter { a -> songs.any { it.genre == genre && it.artist == a } }

    fun songsIn(genre: String?): List<Track> = albums.flatMap { a -> a.tracks.filter { it.genre == genre } }

    companion object {
        /** "5opka, Grechko feat. X" → {5opka, grechko, x}: the artists of one credit line. */
        fun artistSet(credit: String?): Set<String> =
            credit.orEmpty().split(Regex("""\s*(?:,|;|&|/|\s+x\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+featuring\s+|\s+при уч\.?\s+)\s*""", RegexOption.IGNORE_CASE))
                .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

        /**
         * One album whose songs carry different artist lines ("5opka", "5opka, Grechko") is
         * scanned as several. Albums with the same title that share an artist become one;
         * same-titled albums of unrelated artists stay apart.
         */
        fun mergeSplitAlbums(tracks: List<Track>): List<Track> {
            val byKey = tracks.groupBy { it.albumKey }
            val merged = HashMap<String, String>()
            byKey.entries.groupBy { (_, list) -> list.first().album?.trim()?.lowercase() }.forEach { (title, group) ->
                if (title == null || group.size < 2) return@forEach
                // Biggest first: it gives the merged album its key
                val parts = group.sortedByDescending { it.value.size }
                    .map { (key, list) -> key to list.flatMap { t -> artistSet(t.albumArtist ?: t.artist) }.toMutableSet() }
                    .toMutableList()
                val clusters = mutableListOf<Pair<String, MutableSet<String>>>()
                for ((key, artists) in parts) {
                    val home = clusters.firstOrNull { (_, a) -> a.any(artists::contains) }
                    if (home == null) clusters += key to artists
                    else {
                        home.second += artists
                        merged[key] = home.first
                    }
                }
            }
            if (merged.isEmpty()) return tracks
            return tracks.map { t -> merged[t.albumKey]?.let { t.copy(albumKey = it, originalAlbumKey = t.albumKey) } ?: t }
        }

        /** The artist every song of a merged album has, as written on one of them. */
        private fun sharedArtist(list: List<Track>): String? {
            val common = list.map { artistSet(it.albumArtist ?: it.artist) }.reduce { a, b -> a intersect b }
            val name = common.firstOrNull() ?: return null
            return list.firstNotNullOfOrNull { t ->
                (t.albumArtist ?: t.artist).orEmpty().split(Regex("[,;&/]")).map { it.trim() }.firstOrNull { it.lowercase() == name }
            } ?: name
        }

        val EMPTY = LibrarySnapshot(emptyList(), emptyList(), emptyMap(), { null })

        /** Missing names ("Unknown Artist") sort last, like on the iPod. */
        private fun sortKey(s: String?): String {
            if (s == null) return "￿"
            val t = s.trim()
            return if (t.startsWith("the ", ignoreCase = true)) t.substring(4) else t
        }
    }
}
