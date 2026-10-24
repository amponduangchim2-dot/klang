@file:OptIn(UnstableApi::class)

package app.klang.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import app.klang.youtube.YouTube
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Background playback: one ExoPlayer with Klang's audio processor, exposed as a MediaSession. */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val context = this
        val sink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf<AudioProcessor>(KlangAudioProcessor()))
            .build()
        val renderers = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf<Renderer>(
                MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, handler, audioListener, sink)
            )
        }
        // YouTube watch links are resolved to an audio stream only when playback starts,
        // so links in the playlist never go stale.
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val upstream = DefaultDataSource.Factory(context, http)
        val resolving = ResolvingDataSource.Factory(upstream) { spec ->
            val uri = spec.uri.toString()
            if (YouTube.isVideoLink(uri)) {
                val r = YouTube.resolve(uri)
                spec.buildUpon()
                    .setUri(Uri.parse(r.streamUrl))
                    .setHttpRequestHeaders(spec.httpRequestHeaders + mapOf("User-Agent" to YouTube.userAgentFor(r.streamUrl)))
                    .build()
            } else if (spec.uri.scheme == "http" || spec.uri.scheme == "https") {
                // The factory's own user agent would override per-request headers, so set it here.
                spec.withAdditionalHeaders(mapOf("User-Agent" to YouTube.BROWSER_UA))
            } else spec
        }
        val player = ExoPlayer.Builder(context, renderers, DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val sessionActivity = PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaSession.Builder(context, player)
            .setSessionActivity(sessionActivity)
            .setCallback(object : MediaSession.Callback {
                // The controller sends the content URI as the media id; turn it back into a playable item.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>
                ): ListenableFuture<MutableList<MediaItem>> {
                    val resolved = mediaItems.map { item ->
                        if (item.localConfiguration != null) item
                        else item.buildUpon().setUri(item.mediaId).build()
                    }.toMutableList()
                    return Futures.immediateFuture(resolved)
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
