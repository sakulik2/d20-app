package xyz.sakulik.d20.app.domain.common.updater

import java.security.MessageDigest

class Sha256Digest private constructor(private val bytes: ByteArray) {
    fun matches(other: Sha256Digest): Boolean = MessageDigest.isEqual(bytes, other.bytes)

    fun toHex(): String = CharArray(bytes.size * 2).also { output ->
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = HEX_CHARS[value ushr 4]
            output[index * 2 + 1] = HEX_CHARS[value and 0x0f]
        }
    }.concatToString()

    override fun equals(other: Any?): Boolean {
        return other is Sha256Digest && matches(other)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        private const val DIGEST_BYTES = 32
        private const val HEX_CHARS = "0123456789abcdef"
        private val HEX_PATTERN = Regex("^[0-9a-fA-F]{64}$")

        fun parseHex(value: String): Sha256Digest? {
            if (!HEX_PATTERN.matches(value)) return null
            val bytes = ByteArray(DIGEST_BYTES) { index ->
                value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            return Sha256Digest(bytes)
        }

        internal fun fromBytes(bytes: ByteArray): Sha256Digest {
            require(bytes.size == DIGEST_BYTES) { "SHA-256 摘要必须为 32 字节" }
            return Sha256Digest(bytes.copyOf())
        }
    }
}

class Sha256Accumulator {
    private val messageDigest = MessageDigest.getInstance("SHA-256")
    private var isFinished = false

    fun update(buffer: ByteArray, offset: Int, length: Int) {
        check(!isFinished) { "SHA-256 计算已经结束" }
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
            "SHA-256 输入范围无效"
        }
        messageDigest.update(buffer, offset, length)
    }

    fun finish(): Sha256Digest {
        check(!isFinished) { "SHA-256 计算已经结束" }
        isFinished = true
        return Sha256Digest.fromBytes(messageDigest.digest())
    }
}
