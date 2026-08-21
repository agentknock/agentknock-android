package dev.agentknock.presentation

import dev.agentknock.protocol.SoftwareInfo

internal fun renderSoftware(info: SoftwareInfo): String =
    listOf(info.name, info.version).filter(String::isNotBlank).joinToString(" ")
