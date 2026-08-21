package dev.agentknock.protocol

internal fun testClientSoftware(
    applicationVersion: String = "0.1.0",
    libraryVersion: String = applicationVersion,
) = ClientSoftware(
    application = SoftwareInfo("agentknock", applicationVersion),
    library = SoftwareInfo("agentknock", libraryVersion),
)

internal fun testClientSoftwareFields(
    applicationVersion: String = "0.1.0",
    libraryVersion: String = applicationVersion,
): String =
    """"app_info":{"name":"agentknock","version":"$applicationVersion"},"lib_info":{"name":"agentknock","version":"$libraryVersion"}"""
