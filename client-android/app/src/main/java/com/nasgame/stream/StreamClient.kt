package com.nasgame.stream

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import javax.inject.Inject

/**
 * Streams game video from server over WebSocket.
 * Video format: MPEG-TS H.264 (server-side FFmpeg output).
 * Decoded via MediaCodec and rendered onto a Surface (TextureView).
 *
 * Control channel is a separate WebSocket for sending input events.
 */
class StreamClient(
    private val ctx: Context,
    private val serverBase: String,
    private val sessionId: String,
    private val outputSurface: Surface,
) {
    private val client = OkHttpClient()
    private val wsControl: WebSocket
    private val wsVideo: WebSocket
    private val scope = CoroutineScope(Dispatchers.IO)
    private var codec: MediaCodec? = null
    private var running = false

    init {
        val ctrlUrl = serverBase.trimEnd('/')
            .replace("https://", "wss://")
            .replace("http://", "ws://") + "/api/stream/$sessionId/control"
        val vidUrl = ctrlUrl.replace("/control", "/video")

        wsControl = client.newWebSocket(Request.Builder().url(ctrlUrl).build(),
            object : WebSocketListener() {})
        wsVideo = client.newWebSocket(Request.Builder().url(vidUrl).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    feedCodec(bytes.toByteArray())
                }
            })
    }

    private fun feedCodec(data: ByteArray) {
        try {
            codec?.let { c ->
                val idx = c.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val buf = c.getInputBuffer(idx)!!
                    buf.clear()
                    buf.put(data)
                    c.queueInputBuffer(idx, 0, data.size, 0, 0)
                }
                val info = MediaCodec.BufferInfo()
                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    c.releaseOutputBuffer(outIdx, true)
                }
            }
        } catch (_: Exception) {}
    }

    fun start() {
        if (running) return
        running = true
        val fmt = MediaFormat.createVideoFormat("video/avc", 1280, 720)
        codec = MediaCodec.createDecoderByType("video/avc").apply {
            configure(fmt, outputSurface, null, 0)
            start()
        }
    }

    fun sendKey(key: String) {
        wsControl.send("""{"type":"key","key":"$key"}""")
    }

    fun sendJoy(button: String) {
        wsControl.send("""{"type":"joy","button":"$button"}""")
    }

    fun stop() {
        running = false
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        wsControl.close(1000, "bye")
        wsVideo.close(1000, "bye")
        scope.cancel()
    }
}
