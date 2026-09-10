package com.sleepguard.app

import java.io.File
import java.io.FileOutputStream

object WavUtil {
    /** PCM16 单声道 → WAV 文件 */
    fun writeWav(out: File, pcm: ByteArray, sampleRate: Int) {
        val header = ByteArray(44)
        val dataLen = pcm.size
        val totalLen = dataLen + 36
        fun le(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte(); header[off + 1] = (v shr 8 and 0xFF).toByte()
            header[off + 2] = (v shr 16 and 0xFF).toByte(); header[off + 3] = (v shr 24 and 0xFF).toByte()
        }
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        le(4, totalLen)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        le(16, 16); le(20, 1); le(22, 1)
        le(24, sampleRate); le(28, sampleRate * 2); le(32, 2); le(34, 16)
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        le(40, dataLen)
        FileOutputStream(out).use { f -> f.write(header); f.write(pcm) }
    }

    fun shortsToBytes(s: ShortArray): ByteArray {
        val b = ByteArray(s.size * 2)
        for (i in s.indices) {
            b[i * 2] = (s[i].toInt() and 0xFF).toByte()
            b[i * 2 + 1] = (s[i].toInt() shr 8 and 0xFF).toByte()
        }
        return b
    }
}
