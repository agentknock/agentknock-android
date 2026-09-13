package dev.agentknock.presentation

import dev.agentknock.protocol.SoftwareInfo

internal fun renderSoftware(info: SoftwareInfo?): String? =
    listOfNotNull(info?.name, info?.version)
        .filter(String::isNotBlank)
        .joinToString(" ")
        .takeIf(String::isNotEmpty)
