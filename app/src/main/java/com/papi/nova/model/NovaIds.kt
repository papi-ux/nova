package com.papi.nova.model

@JvmInline
value class GameId private constructor(val value: String) {
    companion object {
        val NONE = GameId("")

        operator fun invoke(value: String?): GameId {
            return GameId(value?.trim().orEmpty())
        }
    }
}

@JvmInline
value class ComputerUuid private constructor(val value: String) {
    companion object {
        val NONE = ComputerUuid("")

        operator fun invoke(value: String?): ComputerUuid {
            return ComputerUuid(value?.trim().orEmpty())
        }
    }
}

@JvmInline
value class BitrateKbps(val value: Int)

@JvmInline
value class RefreshRateHz(val value: Float)
