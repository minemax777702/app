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
        val types = generateSequence(error) { it.cause }.take(5).map { it.javaClass.simpleName }.joinToString(" → ")
        val progress = report()
        return "SSH $port · $code\n" + (if (progress.isBlank()) "" else "$progress\n") +
            "✗ $detail" + (if (types.isBlank()) "" else "\nТип ошибки: $types")
    }
}
