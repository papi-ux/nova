package com.papi.nova.api
sealed interface PolarisBitrateWriteResult {
    data class Applied(val encoderKbps:Int,val observed:PolarisSessionStatus):PolarisBitrateWriteResult
    data object SessionChanged:PolarisBitrateWriteResult
    data object Failed:PolarisBitrateWriteResult
}
