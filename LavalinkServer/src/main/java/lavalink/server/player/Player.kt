/*
 * Copyright (c) 2017 Frederik Ar. Mikkelsen & NoobLance
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package lavalink.server.player

import com.sedmelluq.discord.lavaplayer.filter.equalizer.Equalizer
import com.sedmelluq.discord.lavaplayer.filter.equalizer.EqualizerFactory
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import lavalink.server.io.SocketContext
import net.dv8tion.jda.api.audio.AudioSendHandler
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

class Player(val socket: SocketContext, val guildId: String, audioPlayerManager: AudioPlayerManager) : AudioEventAdapter(), AudioSendHandler {
    companion object {
        private val log = LoggerFactory.getLogger(Player::class.java)
    }

    private val player = audioPlayerManager.createPlayer()
    val audioLossCounter = AudioLossCounter()
    private var lastFrame: AudioFrame? = null
    private val equalizerFactory = EqualizerFactory()
    private var isEqualizerApplied = false

    init {
        player.addListener(this)
        player.addListener(EventEmitter(audioPlayerManager, this))
        player.addListener(audioLossCounter)
    }

    fun play(track: AudioTrack?) {
        player.playTrack(track)
    }

    fun stop() {
        player.stopTrack()
    }

    fun setPause(b: Boolean) {
        player.isPaused = b
    }

    fun seekTo(position: Long) {
        val track = player.playingTrack ?: throw RuntimeException("Can't seek when not playing anything")
        track.position = position
    }

    fun setVolume(volume: Int) {
        player.volume = volume
    }

    fun setBandGain(band: Int, gain: Float) {
        log.debug("Setting band {}'s gain to {}", band, gain)
        equalizerFactory.setGain(band, gain)
        if (gain == 0.0f) {
            if (!isEqualizerApplied) {
                return
            }
            var shouldDisable = true
            for (i in 0 until Equalizer.BAND_COUNT) {
                if (equalizerFactory.getGain(i) != 0.0f) {
                    shouldDisable = false
                }
            }
            if (shouldDisable) {
                player.setFilterFactory(null)
                isEqualizerApplied = false
            }
        } else if (!isEqualizerApplied) {
            player.setFilterFactory(equalizerFactory)
            isEqualizerApplied = true
        }
    }

    val state: JSONObject
        get() {
            val json = JSONObject()
            if (player.playingTrack != null) json.put("position", player.playingTrack.position)
            json.put("time", System.currentTimeMillis())
            return json
        }

    val playingTrack: AudioTrack?
        get() = player.playingTrack

    val isPaused: Boolean
        get() = player.isPaused

    override fun canProvide(): Boolean {
        lastFrame = player.provide()
        return if (lastFrame == null) {
            audioLossCounter.onLoss()
            false
        } else {
            audioLossCounter.onSuccess()
            true
        }
    }

    override fun provide20MsAudio(): ByteBuffer {
        return ByteBuffer.wrap(lastFrame!!.data)
    }

    override fun isOpus(): Boolean {
        return true
    }

    val isPlaying: Boolean
        get() = player.playingTrack != null && !player.isPaused

    override fun onTrackStart(player: AudioPlayer, track: AudioTrack) {
        sendPlayerUpdate()
    }

    fun sendPlayerUpdate() {
        val json = JSONObject()
        json.put("op", "playerUpdate")
        json.put("guildId", guildId)
        json.put("state", state)

        socket.send(json)
    }
}