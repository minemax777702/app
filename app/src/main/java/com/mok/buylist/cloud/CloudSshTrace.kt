package com.mok.buylist.cloud

/** Allowlisted, local SSH progress. Never stores raw JSch output, addresses, credentials or packets. */
internal class CloudSshTrace {
    private val steps = linkedSetOf<String>()
    @Synchronized fun step(value: String) { steps.add(value) }
    @Synchronized fun report(): String = steps.joinToString("\n") { "✓ $it" }

    fun logger() = object : com.jcraft.jsch.Logger {
        override fun isEnabled(level: Int) = true
        override fun log(level: Int, message: String) {
            when {
                message == "Connection established" -> step("TCP-соединение установлено")
                message.startsWith("Remote version string: SSH-") -> step("Получен SSH-баннер сервера")
                message == "SSH_MSG_KEXINIT received" -> step("Получены параметры обмена ключами")
                message.startsWith("Host '") && message.contains("is known and matches") -> step("Ключ сервера совпадает с доверенным")
                message == "SSH_MSG_NEWKEYS received" -> step("Защищённый SSH-канал установлен")
                message == "ecdsa-sha2-nistp256 preauth success" -> step("GitHub принял открытый ключ")
                message.startsWith("Authentication succeeded") -> step("Авторизация SSH подтверждена")
            }
        }
    }

    fun failure(port: Int, code: String, detail: String, error: Throwable? = null): String {
        // Local Logcat only: preserve exception classes and stack frames, but not
        // exception messages from the network, credentials, or raw SSH packets.
        val diagnostic = buildString {
            append("SSH ").append(port).append(" · ").append(code).append(": ").append(detail)
            val progress = report()
            if (progress.isNotBlank()) append('\n').append(progress)
            if (error != null) {
                val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
                var current: Throwable? = error
                while (current != null) {
                    val cause = current
                    if (!visited.add(cause)) break
                    append("\nCause: ").append(cause.javaClass.name)
                    if (cause is com.jcraft.jsch.JSchAlgoNegoFailException) {
                        append(" (algorithm negotiation failed)")
                    }
                    cause.stackTrace.forEach { append("\n  at ").append(it) }
                    current = cause.cause
                }
            }
        }
        android.util.Log.e("BuyListSSH", diagnostic)
        return "$detail ($code)"
    }
}
