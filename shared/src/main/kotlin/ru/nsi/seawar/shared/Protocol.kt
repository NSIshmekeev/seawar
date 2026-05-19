package ru.nsi.seawar.shared

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    prettyPrint = false
    encodeDefaults = true
}

fun encodeClientMessage(message: ClientMessage): String = json.encodeToString(message)

fun encodeServerMessage(message: ServerMessage): String = json.encodeToString(message)

fun decodeClientMessage(payload: String): ClientMessage = json.decodeFromString(ClientMessage.serializer(), payload)

fun decodeServerMessage(payload: String): ServerMessage = json.decodeFromString(ServerMessage.serializer(), payload)