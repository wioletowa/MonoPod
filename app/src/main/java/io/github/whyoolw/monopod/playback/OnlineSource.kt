package io.github.whyoolw.monopod.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import io.github.whyoolw.monopod.Monopod
import io.github.whyoolw.monopod.yandex.YandexMusic

/**
 * Where the players read music from: files and content URIs as before, and Yandex Music songs
 * (yandex://track/ID), whose stream link is fetched only when the song is about to play.
 */
@UnstableApi
fun musicSourceFactory(context: Context): DefaultMediaSourceFactory {
    val app = context.applicationContext as Monopod
    val resolver = ResolvingDataSource.Resolver { spec ->
        val id = YandexMusic.trackId(spec.uri.toString()) ?: return@Resolver spec
        spec.withUri(Uri.parse(app.yandex.streamUrl(id)))
    }
    // MP3s without a seek table (Yandex streams, some files) can still seek, as if constant
    // bitrate; the crossfade tail starts in the middle of a song
    val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
    return DefaultMediaSourceFactory(ResolvingDataSource.Factory(DefaultDataSource.Factory(context), resolver), extractors)
}
