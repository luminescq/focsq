package com.csqtt.client

object TunnelLogPolicy {
    fun isAllowedWhenInactiveLogging(key: String, isError: Boolean, level: LogLevel?): Boolean {
        return isError || level == LogLevel.ERR || key == "ready" || key == "stats"
    }

    fun isInternalRecovery(line: String): Boolean {
        if (line.contains("FATAL_", ignoreCase = true)) return false
        return line.contains("GETCONF", ignoreCase = true) ||
            line.contains("[СЕТЬ][RETRY]", ignoreCase = true) ||
            line.contains("[TURN][RETRY]", ignoreCase = true) ||
            line.contains("[HANDSHAKE][RETRY]", ignoreCase = true) ||
            line.contains("PEER_LIVENESS_TIMEOUT", ignoreCase = true) ||
            line.contains("486", ignoreCase = true) ||
            line.contains("437", ignoreCase = true) ||
            (line.contains("turn", ignoreCase = true) && line.contains("timeout", ignoreCase = true)) ||
            (line.contains("allocation", ignoreCase = true) && line.contains("quota", ignoreCase = true)) ||
            (line.contains("allocation", ignoreCase = true) && line.contains("mismatch", ignoreCase = true)) ||
            (line.contains("[КРЕД", ignoreCase = true) && line.contains("Повторяем через", ignoreCase = true))
    }

    fun isTurnStreamFailure(line: String): Boolean {
        if (isInternalRecovery(line)) return false
        return line.contains("[TURN][DOWN]", ignoreCase = true) ||
            (
                line.contains("[ВОРКЕР", ignoreCase = true) &&
                    line.contains("[TURN]", ignoreCase = true) &&
                    (
                        line.contains("ошибка", ignoreCase = true) ||
                            line.contains("failed", ignoreCase = true) ||
                            line.contains("exhausted", ignoreCase = true)
                        )
                )
    }
}
