package ru.nsi.seawar.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.nsi.seawar.shared.BOARD_SIZE
import ru.nsi.seawar.shared.Cell
import ru.nsi.seawar.shared.CellState
import ru.nsi.seawar.shared.ClientMessage
import ru.nsi.seawar.shared.Orientation
import ru.nsi.seawar.shared.ServerMessage
import ru.nsi.seawar.shared.ShipPlacement
import ru.nsi.seawar.shared.defaultFleet
import ru.nsi.seawar.shared.decodeServerMessage
import ru.nsi.seawar.shared.encodeClientMessage
import ru.nsi.seawar.shared.emptyBoardView
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.random.Random

fun main() {
    SwingUtilities.invokeLater {
        SeaWarApp().show()
    }
}

private object Theme {
    val backgroundTop = Color(0xE6, 0xF3, 0xF6)
    val backgroundBottom = Color(0xF5, 0xF0, 0xE6)
    val panelBackground = Color(0xF8, 0xFB, 0xFC)
    val panelBorder = Color(0xD1, 0xE0, 0xE7)
    val accent = Color(0x2B, 0x6C, 0x8A)
    val accentSoft = Color(0xD7, 0xEC, 0xF4)
    val textPrimary = Color(0x1D, 0x2B, 0x34)
    val textMuted = Color(0x4B, 0x5D, 0x68)
    val boardBackground = Color(0xE7, 0xF1, 0xF5)
    val cellUnknown = Color(0x2D, 0x5B, 0x7A)
    val cellMiss = Color(0x8D, 0xA6, 0xB6)
    val cellHit = Color(0xD9, 0x53, 0x3D)
    val cellShip = Color(0x39, 0x9D, 0x8B)
    val cellText = Color(0xF6, 0xF7, 0xF9)
    val titleFont = Font("Fira Sans", Font.BOLD, 24)
    val bodyFont = Font("Fira Sans", Font.PLAIN, 14)
    val smallFont = Font("Fira Sans", Font.PLAIN, 12)
    val monoFont = Font("JetBrains Mono", Font.PLAIN, 12)
}

private class GradientPanel : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        val paint = GradientPaint(0f, 0f, Theme.backgroundTop, 0f, height.toFloat(), Theme.backgroundBottom)
        g2.paint = paint
        g2.fillRect(0, 0, width, height)
        super.paintComponent(g)
    }
}

private data class ClientUiState(
    val connected: Boolean = false,
    val status: String = "Отключен",
    val yourTurn: Boolean = false,
    val winner: String? = null,
    val yourBoard: List<List<CellState>> = emptyBoardView().cells,
    val enemyBoard: List<List<CellState>> = emptyBoardView().cells,
    val placementHint: String = "Поставьте корабль размером ${defaultFleet.first().size}",
    val placementReady: Boolean = false,
    val fleetSubmitted: Boolean = false,
    val orientation: Orientation = Orientation.Horizontal,
    val opponentName: String? = null,
)

private data class PlacementState(
    val placements: MutableList<ShipPlacement> = mutableListOf(),
    var orientation: Orientation = Orientation.Horizontal,
    var submitRequested: Boolean = false,
    var submitted: Boolean = false,
) {
    fun nextShipSize(): Int? = if (placements.size < defaultFleet.size) defaultFleet[placements.size].size else null

    fun isReady(): Boolean = nextShipSize() == null
}

private class SeaWarApp {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = HttpClient(CIO) { install(WebSockets) }
    private var websocketJob: Job? = null
    private var activeSession: DefaultClientWebSocketSession? = null
    private var playerName = "Игрок"
    private val placement = PlacementState()
    private val state = MutableStateFlow(ClientUiState())

    private val window = JFrame("SeaWar")
    private val titleLabel = JLabel("SeaWar")
    private val statusLabel = JLabel("Отключен")
    private val phaseLabel = JLabel("Этап: расстановка флота")
    private val opponentLabel = JLabel("Соперник: -")
    private val placementLabel = JLabel(placementHintText())
    private val fleetLabel = JLabel("Флот: ${defaultFleet.joinToString { it.size.toString() }}")

    private val connectField = JTextField("ws://localhost:8080/ws")
    private val nameField = JTextField("Игрок")
    private val connectButton = JButton("Подключиться")
    private val orientationButton = JButton("Горизонтально")
    private val randomButton = JButton("Случайно")
    private val clearButton = JButton("Очистить")
    private val readyButton = JButton("Подтвердить флот")

    private val yourGrid = BoardPanel { cell -> placeShipAt(cell) }
    private val enemyGrid = BoardPanel { cell -> attack(cell) }

    init {
        buildUi()
        updatePlacementUi()
        bindState()
    }

    fun show() {
        window.isVisible = true
    }

    private fun buildUi() {
        window.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        window.minimumSize = Dimension(1240, 780)

        val root = GradientPanel().apply {
            layout = BorderLayout(16, 16)
            border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        }
        window.contentPane = root

        styleTitle(titleLabel)
        styleLabel(statusLabel)
        styleLabel(phaseLabel, muted = true)
        styleLabel(opponentLabel, muted = true)
        styleLabel(placementLabel, muted = false)
        styleLabel(fleetLabel, muted = true)

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.WEST)
        }

        val headerStatus = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(statusLabel)
            add(phaseLabel)
            add(opponentLabel)
        }
        header.add(headerStatus, BorderLayout.EAST)

        val controls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Theme.panelBackground
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.panelBorder),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)
            )
        }

        val connectionSection = section(
            "Подключение",
            labeledField("Адрес сервера", connectField),
            labeledField("Имя", nameField),
            connectButton,
        )

        val placementSection = section(
            "Расстановка флота",
            fleetLabel,
            placementLabel,
            orientationButton,
            randomButton,
            clearButton,
            readyButton,
        )

        val legendLabel = JLabel("Легенда: ■ корабль, X попадание, • промах").apply {
            font = Theme.smallFont
            foreground = Theme.textMuted
        }

        controls.add(connectionSection)
        controls.add(Box.createVerticalStrut(12))
        controls.add(placementSection)
        controls.add(Box.createVerticalStrut(12))
        controls.add(legendLabel)

        val boards = JPanel(GridLayout(1, 2, 16, 16)).apply {
            isOpaque = false
            add(wrapBoard("Ваше поле", yourGrid))
            add(wrapBoard("Поле соперника", enemyGrid))
        }

        root.add(header, BorderLayout.NORTH)
        root.add(controls, BorderLayout.WEST)
        root.add(boards, BorderLayout.CENTER)

        stylePrimaryButton(connectButton)
        stylePrimaryButton(readyButton)
        styleSecondaryButton(orientationButton)
        styleSecondaryButton(randomButton)
        styleSecondaryButton(clearButton)
        styleTextField(connectField)
        styleTextField(nameField)

        connectButton.addActionListener {
            if (state.value.connected) {
                disconnect()
            } else {
                connect()
            }
        }

        orientationButton.addActionListener { toggleOrientation() }
        randomButton.addActionListener { applyRandomPlacement() }
        clearButton.addActionListener { resetPlacement() }
        readyButton.addActionListener { submitFleet() }

        window.pack()
        window.setLocationRelativeTo(null)
    }

    private fun bindState() {
        scope.launch {
            state.collect { ui ->
                SwingUtilities.invokeLater {
                    statusLabel.text = ui.status
                    phaseLabel.text = if (ui.fleetSubmitted) "Этап: игра" else "Этап: расстановка флота"
                    opponentLabel.text = "Соперник: ${ui.opponentName ?: "-"}"
                    placementLabel.text = ui.placementHint
                    connectButton.text = if (ui.connected) "Отключиться" else "Подключиться"
                    orientationButton.text = if (ui.orientation == Orientation.Horizontal) "Горизонтально" else "Вертикально"
                    readyButton.isEnabled = ui.placementReady && !ui.fleetSubmitted
                    randomButton.isEnabled = !ui.fleetSubmitted
                    clearButton.isEnabled = !ui.fleetSubmitted
                    orientationButton.isEnabled = !ui.fleetSubmitted
                    yourGrid.setBoard(ui.yourBoard, locked = ui.fleetSubmitted)
                    enemyGrid.setBoard(ui.enemyBoard, locked = !ui.yourTurn || !ui.fleetSubmitted)
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
                if (placement.submitRequested && placement.isReady() && !placement.submitted) {
                    session.send(Frame.Text(encodeClientMessage(ClientMessage.PlaceFleet(placement.placements.toList()))))
                    placement.submitted = true
                    placement.submitRequested = false
                    updatePlacementUi()
                }
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
                                opponentName = decoded.opponentName,
                            )
                        }
                        is ServerMessage.State -> state.update { current ->
                            current.copy(
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
        placement.submitted = false
        placement.submitRequested = false
        updatePlacementUi()
        state.update { it.copy(connected = false, status = "Отключен", yourTurn = false, winner = null) }
    }

    private fun attack(cell: Cell) {
        if (!state.value.connected || !state.value.yourTurn || !state.value.fleetSubmitted) return
        scope.launch {
            try {
                activeSession?.send(Frame.Text(encodeClientMessage(ClientMessage.Attack(cell))))
            } catch (_: Exception) {
            }
        }
    }

    private fun placeShipAt(cell: Cell) {
        if (placement.submitted) return
        val shipSize = placement.nextShipSize() ?: return
        val cells = buildList {
            for (index in 0 until shipSize) {
                add(
                    if (placement.orientation == Orientation.Horizontal) {
                        Cell(cell.x + index, cell.y)
                    } else {
                        Cell(cell.x, cell.y + index)
                    }
                )
            }
        }

        val occupied = placement.placements.flatMap { it.cells }.toSet()
        val validation = validatePlacement(cells, occupied)
        if (validation != null) {
            state.update { it.copy(status = validation) }
            return
        }

        placement.placements.add(ShipPlacement(cells))
        updatePlacementUi()
    }

    private fun validatePlacement(cells: List<Cell>, occupied: Set<Cell>): String? {
        if (cells.any { it.x !in 0 until BOARD_SIZE || it.y !in 0 until BOARD_SIZE }) {
            return "Корабль выходит за пределы поля"
        }
        if (cells.any { it in occupied }) {
            return "Корабли не могут пересекаться"
        }
        if (cells.any { cell -> around(cell).any { it in occupied } }) {
            return "Корабли не должны касаться"
        }
        return null
    }

    private fun toggleOrientation() {
        placement.orientation = if (placement.orientation == Orientation.Horizontal) {
            Orientation.Vertical
        } else {
            Orientation.Horizontal
        }
        updatePlacementUi()
    }

    private fun applyRandomPlacement() {
        if (placement.submitted) return
        placement.placements.clear()
        placement.placements.addAll(autoPlaceFleet())
        updatePlacementUi()
    }

    private fun resetPlacement() {
        if (placement.submitted) return
        placement.placements.clear()
        placement.submitRequested = false
        updatePlacementUi()
    }

    private fun submitFleet() {
        if (placement.submitted) return
        if (!placement.isReady()) {
            state.update { it.copy(status = "Сначала расставьте весь флот") }
            return
        }
        if (!state.value.connected || activeSession == null) {
            placement.submitRequested = true
            state.update { it.copy(status = "Подключитесь к серверу, чтобы отправить флот") }
            return
        }
        scope.launch {
            try {
                activeSession?.send(Frame.Text(encodeClientMessage(ClientMessage.PlaceFleet(placement.placements.toList()))))
                placement.submitted = true
                placement.submitRequested = false
                updatePlacementUi()
                state.update { it.copy(status = "Флот отправлен. Ждем соперника...") }
            } catch (_: Exception) {
                state.update { it.copy(status = "Не удалось отправить флот") }
            }
        }
    }

    private fun updatePlacementUi() {
        val nextSize = placement.nextShipSize()
        val hint = when {
            placement.submitted -> "Флот отправлен. Ожидаем соперника."
            nextSize != null -> "Поставьте корабль размером $nextSize"
            else -> "Флот готов. Нажмите \"Подтвердить флот\"."
        }

        state.update { current ->
            val board = if (placement.submitted) {
                current.yourBoard
            } else {
                renderPlacementBoard()
            }
            current.copy(
                yourBoard = board,
                placementHint = hint,
                placementReady = placement.isReady(),
                fleetSubmitted = placement.submitted,
                orientation = placement.orientation,
            )
        }
    }

    private fun renderPlacementBoard(): List<List<CellState>> {
        val board = MutableList(BOARD_SIZE) { MutableList(BOARD_SIZE) { CellState.Unknown } }
        placement.placements.flatMap { it.cells }.forEach { cell ->
            if (cell.y in 0 until BOARD_SIZE && cell.x in 0 until BOARD_SIZE) {
                board[cell.y][cell.x] = CellState.Ship
            }
        }
        return board.map { it.toList() }
    }

    private fun placementHintText(): String = "Поставьте корабль размером ${defaultFleet.first().size}"

    private fun labeledField(label: String, field: JTextField): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        val title = JLabel(label).apply {
            font = Theme.smallFont
            foreground = Theme.textMuted
        }
        add(title, BorderLayout.NORTH)
        add(field, BorderLayout.CENTER)
    }

    private fun section(title: String, vararg components: JComponent): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = Theme.panelBackground
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.panelBorder),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        )
        val titleLabel = JLabel(title).apply {
            font = Theme.bodyFont.deriveFont(Font.BOLD)
            foreground = Theme.textPrimary
        }
        add(titleLabel)
        add(Box.createVerticalStrut(8))
        components.forEachIndexed { index, component ->
            add(component)
            if (index != components.lastIndex) {
                add(Box.createVerticalStrut(8))
            }
        }
    }

    private fun wrapBoard(title: String, panel: BoardPanel): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = Theme.panelBackground
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.panelBorder),
            BorderFactory.createEmptyBorder(12, 12, 12, 12)
        )
        val titleLabel = JLabel(title).apply {
            font = Theme.bodyFont.deriveFont(Font.BOLD)
            foreground = Theme.textPrimary
        }
        add(titleLabel)
        add(Box.createVerticalStrut(8))
        add(createBoardWithAxis(panel))
    }

    private fun createBoardWithAxis(panel: BoardPanel): JPanel {
        val boardContainer = JPanel(BorderLayout()).apply { isOpaque = false }

        val corner = JLabel("", SwingConstants.CENTER).apply {
            preferredSize = Dimension(24, 24)
        }

        val topLabels = JPanel(GridLayout(1, BOARD_SIZE, 2, 2)).apply { isOpaque = false }
        for (index in 0 until BOARD_SIZE) {
            val letter = ('A'.code + index).toChar().toString()
            topLabels.add(axisLabel(letter))
        }

        val topRow = JPanel(BorderLayout()).apply { isOpaque = false }
        topRow.add(corner, BorderLayout.WEST)
        topRow.add(topLabels, BorderLayout.CENTER)

        val leftLabels = JPanel(GridLayout(BOARD_SIZE, 1, 2, 2)).apply { isOpaque = false }
        for (index in 1..BOARD_SIZE) {
            leftLabels.add(axisLabel(index.toString()))
        }
        leftLabels.preferredSize = Dimension(24, panel.preferredSize.height)

        boardContainer.add(topRow, BorderLayout.NORTH)
        boardContainer.add(leftLabels, BorderLayout.WEST)
        boardContainer.add(panel, BorderLayout.CENTER)
        return boardContainer
    }

    private fun axisLabel(text: String): JLabel = JLabel(text, SwingConstants.CENTER).apply {
        font = Theme.smallFont
        foreground = Theme.textMuted
    }

    private fun styleTitle(label: JLabel) {
        label.font = Theme.titleFont
        label.foreground = Theme.textPrimary
    }

    private fun styleLabel(label: JLabel, muted: Boolean = false) {
        label.font = Theme.bodyFont
        label.foreground = if (muted) Theme.textMuted else Theme.textPrimary
    }

    private fun stylePrimaryButton(button: JButton) {
        button.font = Theme.bodyFont
        button.background = Theme.accent
        button.foreground = Color.WHITE
        button.isFocusPainted = false
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        button.margin = Insets(8, 12, 8, 12)
    }

    private fun styleSecondaryButton(button: JButton) {
        button.font = Theme.bodyFont
        button.background = Theme.accentSoft
        button.foreground = Theme.textPrimary
        button.isFocusPainted = false
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        button.margin = Insets(6, 10, 6, 10)
    }

    private fun styleTextField(field: JTextField) {
        field.font = Theme.bodyFont
        field.background = Color.WHITE
        field.foreground = Theme.textPrimary
        field.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.panelBorder),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)
        )
    }
}

private class BoardPanel(
    private val onCellClick: (Cell) -> Unit,
) : JPanel(GridLayout(BOARD_SIZE, BOARD_SIZE, 2, 2)) {
    private val cells = Array(BOARD_SIZE) { y ->
        Array(BOARD_SIZE) { x ->
            JButton().apply {
                preferredSize = Dimension(36, 36)
                margin = Insets(0, 0, 0, 0)
                isFocusPainted = false
                isBorderPainted = false
                isOpaque = true
                font = Theme.monoFont
                foreground = Theme.cellText
                toolTipText = "${('A'.code + x).toChar()}${y + 1}"
                addActionListener { onCellClick(Cell(x, y)) }
            }
        }
    }

    init {
        background = Theme.boardBackground
        border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
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
                    CellState.Unknown -> Theme.cellUnknown
                    CellState.Miss -> Theme.cellMiss
                    CellState.Hit -> Theme.cellHit
                    CellState.Ship -> Theme.cellShip
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
    val random = Random(System.currentTimeMillis())
    while (true) {
        val placements = mutableListOf<ShipPlacement>()
        val occupied = mutableSetOf<Cell>()
        for (ship in defaultFleet) {
            var placed = false
            repeat(200) {
                val horizontal = random.nextBoolean()
                val x = random.nextInt(0, BOARD_SIZE)
                val y = random.nextInt(0, BOARD_SIZE)
                val cells = buildList {
                    for (index in 0 until ship.size) {
                        add(if (horizontal) Cell(x + index, y) else Cell(x, y + index))
                    }
                }
                if (cells.any { it.x !in 0 until BOARD_SIZE || it.y !in 0 until BOARD_SIZE }) return@repeat
                if (cells.any { it in occupied }) return@repeat
                if (cells.any { cell -> around(cell).any { it in occupied } }) return@repeat
                placements += ShipPlacement(cells)
                occupied += cells
                placed = true
                return@repeat
            }
            if (!placed) {
                placements.clear()
                occupied.clear()
                break
            }
        }
        if (placements.size == defaultFleet.size) {
            return placements
        }
    }
}

private fun around(cell: Cell): List<Cell> = buildList {
    for (dy in -1..1) {
        for (dx in -1..1) {
            val candidate = Cell(cell.x + dx, cell.y + dy)
            if (candidate.x in 0 until BOARD_SIZE && candidate.y in 0 until BOARD_SIZE) {
                add(candidate)
            }
        }
    }
}