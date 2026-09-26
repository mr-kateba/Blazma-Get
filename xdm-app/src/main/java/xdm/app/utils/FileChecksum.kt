package xdm.app.utils

import java.io.File
import java.security.MessageDigest

/** Hash of a downloaded file, to compare with the checksum a website publishes. */
object FileChecksum {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 16).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
