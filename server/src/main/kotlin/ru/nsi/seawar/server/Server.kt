package ru.nsi.seawar.server

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.consumeEach
import ru.nsi.seawar.shared.BOARD_SIZE
import ru.nsi.seawar.shared.Cell
import ru.nsi.seawar.shared.CellState
import ru.nsi.seawar.shared.ClientMessage
import ru.nsi.seawar.shared.ServerMessage
import ru.nsi.seawar.shared.createBoardView
import ru.nsi.seawar.shared.decodeClientMessage
import ru.nsi.seawar.shared.encodeServerMessage
import ru.nsi.seawar.shared.validateFleet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

fun main() {
    printNetworkAddresses()
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module).start(wait = true)
}

private fun printNetworkAddresses() {
    println("=".repeat(50))
    println("  SeaWar — Сервер запущен на порту 8080")
    println("=".repeat(50))
    try {
        val addresses = java.net.NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.filterIsInstance<java.net.Inet4Address>()
            ?.map { it.hostAddress }
            ?.toList()
            ?: emptyList()
        if (addresses.isEmpty()) {
            println("  Адрес в сети: не найден (только localhost)")
            println("  URL для подключения: ws://localhost:8080/ws")
        } else {
            println("  Передайте второму игроку один из этих адресов:")
            addresses.forEach { ip ->
                println("  --> ws://$ip:8080/ws")
            }
        }
    } catch (_: Exception) {
        println("  URL: ws://localhost:8080/ws")
    }
    println("=".repeat(50))
}

fun Application.module() {
    install(WebSockets)
    routing {
        webSocket("/ws") {
            SeaWarServer.handleSession(this)
        }
    }
}

private class SeaWarServer {
    private val players = ConcurrentHashMap<String, PlayerSession>()
    private val matchLock = Any()
    private val queue = ArrayDeque<PlayerSession>()
    private val idSequence = AtomicInteger(1)

    companion object {
        private val singleton = SeaWarServer()

        suspend fun handleSession(session: WebSocketSession) {
            singleton.handle(session)
        }
    }

    private suspend fun handle(session: WebSocketSession) {
        var player: PlayerSession? = null
        try {
            session.incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    val message = decodeClientMessage(frame.readText())
                    when (message) {
                        is ClientMessage.Join -> {
                            if (player != null) return@consumeEach
                            val assignedId = "player-${idSequence.getAndIncrement()}"
                            player = PlayerSession(
                                id = assignedId,
                                name = message.name.ifBlank { "Игрок $assignedId" },
                                session = session,
                            ).also { players[it.id] = it }
                            session.sendEncoded(ServerMessage.Welcome(player!!.id))
                            enqueueAndMatch(player!!)
                        }

                        is ClientMessage.PlaceFleet -> handleFleetPlacement(player, message)
                        is ClientMessage.Attack -> handleAttack(player, message.cell)
                    }
                }
            }
        } finally {
            player?.let { removePlayer(it) }
        }
    }

    private suspend fun enqueueAndMatch(player: PlayerSession) {
        var waitingMessage: String? = null
        var match: Pair<PlayerSession, PlayerSession>? = null
        synchronized(matchLock) {
            if (player.room != null) return
            if (player !in queue) {
                queue.addLast(player)
            }
            if (queue.size >= 2) {
                val first = queue.removeFirst()
                val second = queue.removeFirst()
                val room = GameRoom(first, second)
                first.room = room
                second.room = room
                match = first to second
            } else {
                waitingMessage = "Ожидание второго игрока..."
            }
        }

        waitingMessage?.let { player.send(ServerMessage.RoomWaiting(it)) }

        match?.let { (first, second) ->
            first.send(ServerMessage.GameReady(yourTurn = true, opponentName = second.name))
            second.send(ServerMessage.GameReady(yourTurn = false, opponentName = first.name))
            if (first.ready && second.ready) {
                first.sendState()
                second.sendState()
            } else {
                if (!first.ready) {
                    first.send(ServerMessage.RoomWaiting("Соперник найден. Расставьте флот."))
                } else {
                    first.send(ServerMessage.RoomWaiting("Ожидаем, пока соперник расставит флот."))
                }
                if (!second.ready) {
                    second.send(ServerMessage.RoomWaiting("Соперник найден. Расставьте флот."))
                } else {
                    second.send(ServerMessage.RoomWaiting("Ожидаем, пока соперник расставит флот."))
                }
            }
        }
    }

    private suspend fun handleFleetPlacement(player: PlayerSession?, message: ClientMessage.PlaceFleet) {
        val currentPlayer = player ?: return
        if (currentPlayer.ready) {
            currentPlayer.send(ServerMessage.Error("Флот уже отправлен"))
            return
        }

        val fleet = validateFleet(message.ships).getOrElse {
            currentPlayer.send(ServerMessage.Error(it.message ?: "Неверная расстановка"))
            return
        }

        currentPlayer.shipCells = fleet
        currentPlayer.ready = true
        currentPlayer.send(ServerMessage.RoomWaiting("Флот принят. Ждем соперника..."))

        val room = currentPlayer.room ?: return
        val opponent = room.opponentOf(currentPlayer) ?: return
        if (opponent.ready) {
            room.left.sendState()
            room.right.sendState()
        } else {
            opponent.send(ServerMessage.RoomWaiting("Соперник расставил флот. Расставьте свой."))
        }
    }

    private suspend fun handleAttack(player: PlayerSession?, cell: Cell) {
        val currentPlayer = player ?: return
        val room = currentPlayer.room ?: return
        val opponent = room.opponentOf(currentPlayer) ?: return
        if (!room.isYourTurn(currentPlayer)) {
            currentPlayer.send(ServerMessage.Error("Сейчас не ваш ход"))
            return
        }
        if (!currentPlayer.ready || !opponent.ready) {
            currentPlayer.send(ServerMessage.Error("Оба игрока должны расставить флот"))
            return
        }
        if (room.over) return

        if (cell.x !in 0 until BOARD_SIZE || cell.y !in 0 until BOARD_SIZE) {
            currentPlayer.send(ServerMessage.Error("Клетка вне поля"))
            return
        }

        if (room.shotsBy(currentPlayer).containsKey(cell)) {
            currentPlayer.send(ServerMessage.Error("Клетка уже простреляна"))
            return
        }

        val hit = cell in opponent.shipCells
        room.recordShot(currentPlayer, cell, if (hit) CellState.Hit else CellState.Miss)
        if (!hit) {
            room.switchTurn()
        }

        if (opponent.shipCells.all { room.shotsBy(currentPlayer)[it] == CellState.Hit }) {
            room.over = true
            currentPlayer.sendState(winner = currentPlayer.name)
            opponent.sendState(winner = currentPlayer.name)
            return
        }

        currentPlayer.sendState()
        opponent.sendState()
    }

    private suspend fun removePlayer(player: PlayerSession) {
        players.remove(player.id)
        synchronized(matchLock) {
            queue.remove(player)
        }
        val room = player.room ?: return
        room.over = true
        room.other(player)?.send(ServerMessage.Error("Соперник отключился"))
        room.other(player)?.sendState(winner = room.other(player)?.name)
    }

    private suspend fun PlayerSession.send(message: ServerMessage) {
        session.sendEncoded(message)
    }

    private suspend fun PlayerSession.sendState(winner: String? = null) {
        val room = room ?: return
        val opponent = room.opponentOf(this) ?: return
        val yourShots = room.shotsBy(this)
        val enemyShots = room.shotsBy(opponent)
        val yourView = createBoardView(shipCells, enemyShots, revealShips = true)
        val enemyView = createBoardView(opponent.shipCells, yourShots, revealShips = false)
        send(
            ServerMessage.State(
                yourBoard = yourView,
                enemyBoard = enemyView,
                status = when {
                    winner != null -> if (winner == name) "Вы победили" else "Вы проиграли"
                    room.over -> "Игра завершена"
                    room.isYourTurn(this) -> "Ваш ход"
                    else -> "Ход соперника"
                },
                yourTurn = room.isYourTurn(this),
                winner = winner,
            )
        )
    }
}

private data class PlayerSession(
    val id: String,
    val name: String,
    val session: WebSocketSession,
) {
    var room: GameRoom? = null
    var ready: Boolean = false
    var shipCells: Set<Cell> = emptySet()
}

private class GameRoom(val left: PlayerSession, val right: PlayerSession) {
    private val shots = ConcurrentHashMap<String, MutableMap<Cell, CellState>>()
    private var turnId: String = left.id
    var over: Boolean = false

    init {
        shots[left.id] = ConcurrentHashMap()
        shots[right.id] = ConcurrentHashMap()
    }

    fun opponentOf(player: PlayerSession): PlayerSession? = when (player.id) {
        left.id -> right
        right.id -> left
        else -> null
    }

    fun other(player: PlayerSession): PlayerSession? = opponentOf(player)

    fun isYourTurn(player: PlayerSession): Boolean = turnId == player.id

    fun switchTurn() {
        turnId = if (turnId == left.id) right.id else left.id
    }

    fun shotsBy(player: PlayerSession): MutableMap<Cell, CellState> = shots[player.id] ?: mutableMapOf()

    fun recordShot(player: PlayerSession, cell: Cell, state: CellState) {
        shotsBy(player)[cell] = state
    }
}

private suspend fun WebSocketSession.sendEncoded(message: ServerMessage) {
    send(Frame.Text(encodeServerMessage(message)))
}