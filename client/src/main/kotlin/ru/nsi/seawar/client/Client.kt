package ru.nsi.seawar.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.nsi.seawar.shared.BOARD_SIZE
import ru.nsi.seawar.shared.Cell
import ru.nsi.seawar.shared.CellState
import ru.nsi.seawar.shared.ClientMessage
import ru.nsi.seawar.shared.ServerMessage
import ru.nsi.seawar.shared.ShipPlacement
import ru.nsi.seawar.shared.defaultFleet
import ru.nsi.seawar.shared.decodeServerMessage
import ru.nsi.seawar.shared.encodeClientMessage
import ru.nsi.seawar.shared.emptyBoardView
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

fun main() {
    SwingUtilities.invokeLater {
        SeaWarApp().show()
    }
}

private data class ClientUiState(
    val connected: Boolean = false,
    val status: String = "Отключен",
    val yourTurn: Boolean = false,
    val winner: String? = null,
    val yourBoard: List<List<CellState>> = emptyBoardView().cells,
    val enemyBoard: List<List<CellState>> = emptyBoardView().cells,
)

private class SeaWarApp {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = HttpClient(CIO) { install(WebSockets) }
    private var websocketJob: Job? = null
    private var activeSession: DefaultClientWebSocketSession? = null
    private var playerName = "Игрок"
    private val ships = autoPlaceFleet()
    private val state = MutableStateFlow(ClientUiState())

    private val window = JFrame("SeaWar")
    private val statusLabel = JLabel("Отключен")
    private val connectField = JTextField("ws://localhost:8080/ws")
    private val nameField = JTextField("Игрок")
    private val connectButton = JButton("Подключиться")
    private val yourGrid = BoardPanel { _ -> }
    private val enemyGrid = BoardPanel { cell -> attack(cell) }

    init {
        buildUi()
        bindState()
    }

    fun show() {
        window.isVisible = true
    }

    private fun buildUi() {
        window.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        window.minimumSize = Dimension(1100, 720)
        window.layout = BorderLayout(12, 12)

        val controls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            add(labeledField("Адрес сервера", connectField))
            add(labeledField("Имя", nameField))
            add(connectButton)
            add(statusLabel)
        }

        val boards = JPanel(GridLayout(1, 2, 12, 12)).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            add(wrapBoard("Ваше поле", yourGrid))
            add(wrapBoard("Поле соперника", enemyGrid))
        }

        window.add(controls, BorderLayout.WEST)
        window.add(boards, BorderLayout.CENTER)
        window.pack()
        window.setLocationRelativeTo(null)

        connectButton.addActionListener {
            if (state.value.connected) {
                disconnect()
            } else {
                connect()
            }
        }
    }

    private fun bindState() {
        scope.launch {
            state.collect { ui ->
                SwingUtilities.invokeLater {
                    statusLabel.text = ui.status
                    connectButton.text = if (ui.connected) "Отключиться" else "Подключиться"
                    yourGrid.setBoard(ui.yourBoard, locked = true)
                    enemyGrid.setBoard(ui.enemyBoard, locked = !ui.yourTurn)
                }
            }
        }
    }

    private fun connect() {
        playerName = nameField.text.ifBlank { "Игрок" }
        websocketJob?.cancel()
        websocketJob = scope.launch {
            try {
                val session = client.webSocketSession(connectField.text.trim())
                activeSession = session
                state.update { it.copy(connected = true, status = "Соединено. Отправка данных...", winner = null) }
                session.send(Frame.Text(encodeClientMessage(ClientMessage.Join(playerName))))
                session.send(Frame.Text(encodeClientMessage(ClientMessage.PlaceFleet(ships))))
                while (true) {
                    val frame = session.incoming.receive() as? Frame.Text ?: continue
                    val decoded = decodeServerMessage(frame.readText())
                    when (decoded) {
                        is ServerMessage.Welcome -> state.update { it.copy(connected = true) }
                        is ServerMessage.RoomWaiting -> state.update { it.copy(status = decoded.message) }
                        is ServerMessage.GameReady -> state.update {
                            it.copy(
                                status = if (decoded.yourTurn) "Ваш ход" else "Ход соперника",
                                yourTurn = decoded.yourTurn,
                            )
                        }
                        is ServerMessage.State -> state.update {
                            it.copy(
                                status = decoded.status,
                                yourTurn = decoded.yourTurn,
                                winner = decoded.winner,
                                yourBoard = decoded.yourBoard.cells,
                                enemyBoard = decoded.enemyBoard.cells,
                            )
                        }
                        is ServerMessage.Error -> state.update { it.copy(status = decoded.message) }
                    }
                }
            } catch (error: Exception) {
                val reason = error.message ?: "ошибка"
                state.update { it.copy(connected = false, status = "Нет соединения: $reason") }
            } finally {
                activeSession = null
            }
        }
    }

    private fun disconnect() {
        scope.launch {
            activeSession?.close()
        }
        websocketJob?.cancel()
        websocketJob = null
        activeSession = null
        state.update { ClientUiState(status = "Отключен") }
    }

    private fun attack(cell: Cell) {
        if (!state.value.connected || !state.value.yourTurn) return
        scope.launch {
            try {
                activeSession?.send(Frame.Text(encodeClientMessage(ClientMessage.Attack(cell))))
            } catch (_: Exception) {
            }
        }
    }

    private fun labeledField(label: String, field: JTextField): JPanel = JPanel(BorderLayout()).apply {
        add(JLabel(label), BorderLayout.NORTH)
        add(field, BorderLayout.CENTER)
    }

    private fun wrapBoard(title: String, panel: BoardPanel): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createTitledBorder(title)
        add(panel, BorderLayout.CENTER)
    }
}

private class BoardPanel(
    private val onCellClick: (Cell) -> Unit,
) : JPanel(GridLayout(BOARD_SIZE, BOARD_SIZE, 2, 2)) {
    private val cells = Array(BOARD_SIZE) { y ->
        Array(BOARD_SIZE) { x ->
            JButton().apply {
                preferredSize = Dimension(42, 42)
                addActionListener { onCellClick(Cell(x, y)) }
            }
        }
    }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        cells.forEach { row -> row.forEach { add(it) } }
        refresh(emptyBoardView().cells, locked = true)
    }

    fun setBoard(board: List<List<CellState>>, locked: Boolean) {
        refresh(board, locked)
    }

    private fun refresh(board: List<List<CellState>>, locked: Boolean) {
        for (y in 0 until BOARD_SIZE) {
            for (x in 0 until BOARD_SIZE) {
                val button = cells[y][x]
                val state = board.getOrNull(y)?.getOrNull(x) ?: CellState.Unknown
                button.background = when (state) {
                    CellState.Unknown -> Color(0x2B, 0x4D, 0x66)
                    CellState.Miss -> Color(0x7A, 0x94, 0xA8)
                    CellState.Hit -> Color(0xD9, 0x4F, 0x3D)
                    CellState.Ship -> Color(0x39, 0x9D, 0x8B)
                }
                button.isEnabled = !locked && state == CellState.Unknown
                button.text = when (state) {
                    CellState.Hit -> "X"
                    CellState.Miss -> "•"
                    CellState.Ship -> "■"
                    else -> ""
                }
            }
        }
        repaint()
    }
}

private fun autoPlaceFleet(): List<ShipPlacement> {
    val placements = mutableListOf<ShipPlacement>()
    val occupied = mutableSetOf<Cell>()
    val fleetSizes = defaultFleet.map { it.size }
    var cursor = 0
    for (size in fleetSizes) {
        var placed = false
        while (!placed) {
            val horizontal = cursor % 2 == 0
            val x = cursor % (BOARD_SIZE - size + 1)
            val y = (cursor / 2) % BOARD_SIZE
            val cells = buildList {
                for (index in 0 until size) {
                    add(if (horizontal) Cell(x + index, y) else Cell(x, y + index))
                }
            }
            val valid = cells.all { it.x in 0 until BOARD_SIZE && it.y in 0 until BOARD_SIZE } &&
                cells.none { it in occupied } && cells.flatMap { around(it) }.none { it in occupied }
            if (valid) {
                placements += ShipPlacement(cells)
                occupied += cells
                placed = true
            }
            cursor++
        }
    }
    return placements
}

private fun around(cell: Cell): List<Cell> = buildList {
    for (dy in -1..1) {
        for (dx in -1..1) {
            add(Cell(cell.x + dx, cell.y + dy))
        }
    }
}