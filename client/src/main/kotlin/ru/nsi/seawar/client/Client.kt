package ru.nsi.seawar.client

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlin.system.exitProcess
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import kotlin.random.Random

fun main() = application {
    val viewModel = remember { SeaWarViewModel() }
    DisposableEffect(Unit) {
        onDispose { viewModel.shutdown() }
    }
    Window(onCloseRequest = {
        viewModel.shutdown()
        exitProcess(0)
    }, title = "SeaWar") {
        SeaWarApp(viewModel)
    }
}

private object SeaWarTheme {
    val gradient = Brush.verticalGradient(
        colors = listOf(Color(0xFFE5F3F6), Color(0xFFF7EFE2))
    )
    val surface = Color(0xFFF8FBFC)
    val border = Color(0xFFCCDCE5)
    val accent = Color(0xFF2B6C8A)
    val accentSoft = Color(0xFFD7ECF4)
    val textPrimary = Color(0xFF1D2B34)
    val textMuted = Color(0xFF4B5D68)
    val boardBackground = Color(0xFFE7F1F5)
    val cellUnknown = Color(0xFF2D5B7A)
    val cellMiss = Color(0xFF8DA6B6)
    val cellHit = Color(0xFFD9533D)
    val cellShip = Color(0xFF399D8B)
    val cellText = Color(0xFFF6F7F9)
    val titleFont = FontFamily.Serif
    val bodyFont = FontFamily.SansSerif
    val monoFont = FontFamily.Monospace
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
    val placementGenerating: Boolean = false,
    val fleetSubmitted: Boolean = false,
    val orientation: Orientation = Orientation.Horizontal,
    val opponentName: String? = null,
)

private data class PlacementState(
    val placements: MutableList<ShipPlacement> = mutableListOf(),
    var orientation: Orientation = Orientation.Horizontal,
    var submitRequested: Boolean = false,
    var submitted: Boolean = false,
    var generating: Boolean = false,
) {
    fun nextShipSize(): Int? = if (placements.size < defaultFleet.size) defaultFleet[placements.size].size else null

    fun isReady(): Boolean = nextShipSize() == null
}

private class SeaWarViewModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = HttpClient(CIO) { install(WebSockets) }
    private var websocketJob: Job? = null
    private var activeSession: DefaultClientWebSocketSession? = null
    private val placement = PlacementState()
    private val _state = MutableStateFlow(ClientUiState())
    val state: StateFlow<ClientUiState> = _state.asStateFlow()

    fun connect(url: String, name: String) {
        websocketJob?.cancel()
        websocketJob = scope.launch {
            try {
                val session = client.webSocketSession(url)
                activeSession = session
                _state.update { it.copy(connected = true, status = "Соединено. Отправка данных...", winner = null) }
                session.send(Frame.Text(encodeClientMessage(ClientMessage.Join(name))))
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
                        is ServerMessage.Welcome -> _state.update { it.copy(connected = true) }
                        is ServerMessage.RoomWaiting -> _state.update { it.copy(status = decoded.message) }
                        is ServerMessage.GameReady -> _state.update {
                            it.copy(
                                status = if (decoded.yourTurn) "Ваш ход" else "Ход соперника",
                                yourTurn = decoded.yourTurn,
                                opponentName = decoded.opponentName,
                            )
                        }
                        is ServerMessage.State -> _state.update { current ->
                            current.copy(
                                status = decoded.status,
                                yourTurn = decoded.yourTurn,
                                winner = decoded.winner,
                                yourBoard = decoded.yourBoard.cells,
                                enemyBoard = decoded.enemyBoard.cells,
                            )
                        }
                        is ServerMessage.Error -> _state.update { it.copy(status = decoded.message) }
                    }
                }
            } catch (error: Exception) {
                val reason = error.message ?: "ошибка"
                _state.update { it.copy(connected = false, status = "Нет соединения: $reason") }
            } finally {
                activeSession = null
            }
        }
    }

    fun disconnect() {
        scope.launch {
            activeSession?.close()
        }
        websocketJob?.cancel()
        websocketJob = null
        activeSession = null
        placement.submitted = false
        placement.submitRequested = false
        updatePlacementUi()
        _state.update { it.copy(connected = false, status = "Отключен", yourTurn = false, winner = null) }
    }

    fun placeShipAt(cell: Cell) {
        if (placement.submitted || placement.generating) return
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
            _state.update { it.copy(status = validation) }
            return
        }

        placement.placements.add(ShipPlacement(cells))
        updatePlacementUi()
    }

    fun toggleOrientation() {
        placement.orientation = if (placement.orientation == Orientation.Horizontal) {
            Orientation.Vertical
        } else {
            Orientation.Horizontal
        }
        updatePlacementUi()
    }

    fun applyRandomPlacement() {
        if (placement.submitted || placement.generating) return
        placement.generating = true
        placement.submitRequested = false
        updatePlacementUi()
        scope.launch(Dispatchers.Default) {
            val generated = generateRandomFleet()
            if (generated != null) {
                placement.placements.clear()
                placement.placements.addAll(generated)
            }
            placement.generating = false
            if (generated == null) {
                _state.update { it.copy(status = "Не удалось подобрать случайную расстановку") }
            }
            updatePlacementUi()
        }
    }

    fun resetPlacement() {
        if (placement.submitted || placement.generating) return
        placement.placements.clear()
        placement.submitRequested = false
        updatePlacementUi()
    }

    fun submitFleet() {
        if (placement.submitted) return
        if (!placement.isReady()) {
            _state.update { it.copy(status = "Сначала расставьте весь флот") }
            return
        }
        val session = activeSession
        if (session == null) {
            placement.submitRequested = true
            _state.update { it.copy(status = "Подключитесь к серверу, чтобы отправить флот") }
            return
        }
        scope.launch {
            try {
                session.send(Frame.Text(encodeClientMessage(ClientMessage.PlaceFleet(placement.placements.toList()))))
                placement.submitted = true
                placement.submitRequested = false
                updatePlacementUi()
                _state.update { it.copy(status = "Флот отправлен. Ждем соперника...") }
            } catch (_: Exception) {
                _state.update { it.copy(status = "Не удалось отправить флот") }
            }
        }
    }

    fun attack(cell: Cell) {
        val snapshot = _state.value
        if (!snapshot.connected || !snapshot.yourTurn || !snapshot.fleetSubmitted) return
        scope.launch {
            try {
                activeSession?.send(Frame.Text(encodeClientMessage(ClientMessage.Attack(cell))))
            } catch (_: Exception) {
            }
        }
    }

    fun shutdown() {
        disconnect()
        scope.cancel()
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

    private fun updatePlacementUi() {
        val nextSize = placement.nextShipSize()
        val hint = when {
            placement.generating -> "Генерируем случайную расстановку..."
            placement.submitted -> "Флот отправлен. Ожидаем соперника."
            nextSize != null -> "Поставьте корабль размером $nextSize"
            else -> "Флот готов. Нажмите \"Подтвердить флот\"."
        }

        _state.update { current ->
            val board = if (placement.submitted) {
                current.yourBoard
            } else {
                renderPlacementBoard()
            }
            current.copy(
                yourBoard = board,
                placementHint = hint,
                placementReady = placement.isReady() && !placement.generating,
                placementGenerating = placement.generating,
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

    private fun generateRandomFleet(): List<ShipPlacement>? {
        val random = Random(System.currentTimeMillis())
        repeat(400) {
            val placements = mutableListOf<ShipPlacement>()
            val occupied = mutableSetOf<Cell>()
            var success = true
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
                    if (!canPlace(cells, occupied)) return@repeat
                    placements += ShipPlacement(cells)
                    occupied += cells
                    placed = true
                    return@repeat
                }
                if (!placed) {
                    success = false
                    break
                }
            }
            if (success) {
                return placements
            }
        }
        return generateDeterministicFleet()
    }

    private fun generateDeterministicFleet(): List<ShipPlacement>? {
        val placements = mutableListOf<ShipPlacement>()
        val occupied = mutableSetOf<Cell>()
        for ((index, ship) in defaultFleet.withIndex()) {
            var placed = false
            val orientationOrder = if (index % 2 == 0) {
                listOf(Orientation.Horizontal, Orientation.Vertical)
            } else {
                listOf(Orientation.Vertical, Orientation.Horizontal)
            }
            for (y in 0 until BOARD_SIZE) {
                for (x in 0 until BOARD_SIZE) {
                    for (orientation in orientationOrder) {
                        val cells = buildList {
                            for (offset in 0 until ship.size) {
                                add(
                                    if (orientation == Orientation.Horizontal) {
                                        Cell(x + offset, y)
                                    } else {
                                        Cell(x, y + offset)
                                    }
                                )
                            }
                        }
                        if (!canPlace(cells, occupied)) continue
                        placements += ShipPlacement(cells)
                        occupied += cells
                        placed = true
                        break
                    }
                    if (placed) break
                }
                if (placed) break
            }
            if (!placed) {
                return null
            }
        }
        return placements
    }

    private fun canPlace(cells: List<Cell>, occupied: Set<Cell>): Boolean {
        if (cells.any { it.x !in 0 until BOARD_SIZE || it.y !in 0 until BOARD_SIZE }) return false
        if (cells.any { it in occupied }) return false
        if (cells.any { cell -> around(cell).any { it in occupied } }) return false
        return true
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
}

@Composable
private fun SeaWarApp(viewModel: SeaWarViewModel) {
    val uiState by viewModel.state.collectAsState()
    var serverUrl by remember { mutableStateOf("ws://localhost:8080/ws") }
    var playerName by remember { mutableStateOf("Игрок") }

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SeaWarTheme.gradient)
                .padding(20.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Header(uiState)
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ControlPanel(
                        modifier = Modifier.width(320.dp).fillMaxHeight(),
                        uiState = uiState,
                        serverUrl = serverUrl,
                        onServerUrlChange = { serverUrl = it },
                        playerName = playerName,
                        onPlayerNameChange = { playerName = it },
                        onConnect = { viewModel.connect(serverUrl, playerName) },
                        onDisconnect = { viewModel.disconnect() },
                        onToggleOrientation = { viewModel.toggleOrientation() },
                        onRandom = { viewModel.applyRandomPlacement() },
                        onClear = { viewModel.resetPlacement() },
                        onSubmit = { viewModel.submitFleet() },
                    )
                    BoardsPanel(
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        uiState = uiState,
                        onPlaceShip = { viewModel.placeShipAt(it) },
                        onAttack = { viewModel.attack(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(uiState: ClientUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "SeaWar",
                fontFamily = SeaWarTheme.titleFont,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = SeaWarTheme.textPrimary
            )
            Text(
                text = uiState.status,
                fontFamily = SeaWarTheme.bodyFont,
                fontSize = 14.sp,
                color = SeaWarTheme.textMuted
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            StatusPill(text = if (uiState.fleetSubmitted) "Игра" else "Расстановка")
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Соперник: ${uiState.opponentName ?: "-"}",
                fontFamily = SeaWarTheme.bodyFont,
                fontSize = 13.sp,
                color = SeaWarTheme.textMuted
            )
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(SeaWarTheme.accentSoft)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            fontFamily = SeaWarTheme.bodyFont,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = SeaWarTheme.textPrimary
        )
    }
}

@Composable
private fun ControlPanel(
    modifier: Modifier,
    uiState: ClientUiState,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    playerName: String,
    onPlayerNameChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleOrientation: () -> Unit,
    onRandom: () -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(SeaWarTheme.surface)
            .border(1.dp, SeaWarTheme.border, RoundedCornerShape(18.dp))
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard(title = "Подключение") {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text("Адрес сервера") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Для игры по сети замените localhost на IP-адрес хоста (пример: ws://192.168.1.5:8080/ws). IP хоста отображается в консоли сервера.",
                fontFamily = SeaWarTheme.bodyFont,
                fontSize = 11.sp,
                color = SeaWarTheme.textMuted
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = playerName,
                onValueChange = onPlayerNameChange,
                label = { Text("Имя") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            PrimaryButton(
                text = if (uiState.connected) "Отключиться" else "Подключиться",
                onClick = if (uiState.connected) onDisconnect else onConnect,
                enabled = true,
            )
        }

        SectionCard(title = "Расстановка флота") {
            Text(
                text = "Флот: ${defaultFleet.joinToString { it.size.toString() }}",
                fontFamily = SeaWarTheme.bodyFont,
                fontSize = 13.sp,
                color = SeaWarTheme.textMuted
            )
            Text(
                text = uiState.placementHint,
                fontFamily = SeaWarTheme.bodyFont,
                fontSize = 14.sp,
                color = SeaWarTheme.textPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryButton(
                text = if (uiState.orientation == Orientation.Horizontal) "Горизонтально" else "Вертикально",
                onClick = onToggleOrientation,
                enabled = !uiState.fleetSubmitted && !uiState.placementGenerating
            )
            Spacer(modifier = Modifier.height(6.dp))
            SecondaryButton(
                text = "Случайно",
                onClick = onRandom,
                enabled = !uiState.fleetSubmitted && !uiState.placementGenerating
            )
            Spacer(modifier = Modifier.height(6.dp))
            SecondaryButton(
                text = "Очистить",
                onClick = onClear,
                enabled = !uiState.fleetSubmitted && !uiState.placementGenerating
            )
            Spacer(modifier = Modifier.height(10.dp))
            PrimaryButton(
                text = "Подтвердить флот",
                onClick = onSubmit,
                enabled = uiState.placementReady && !uiState.fleetSubmitted && !uiState.placementGenerating
            )
        }

        SectionCard(title = "Легенда") {
            Text(
                text = "■ корабль  • промах  X попадание",
                fontFamily = SeaWarTheme.monoFont,
                fontSize = 12.sp,
                color = SeaWarTheme.textMuted
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = SeaWarTheme.surface,
        elevation = 0.dp
    ) {
        Column {
            Text(
                text = title,
                fontFamily = SeaWarTheme.bodyFont,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = SeaWarTheme.textPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = SeaWarTheme.accent,
            contentColor = Color.White
        )
    ) {
        Text(text = text, fontFamily = SeaWarTheme.bodyFont, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit, enabled: Boolean) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = SeaWarTheme.accentSoft,
            contentColor = SeaWarTheme.textPrimary
        )
    ) {
        Text(text = text, fontFamily = SeaWarTheme.bodyFont)
    }
}

@Composable
private fun BoardsPanel(
    modifier: Modifier,
    uiState: ClientUiState,
    onPlaceShip: (Cell) -> Unit,
    onAttack: (Cell) -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BoardCard(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            title = "Ваше поле",
            board = uiState.yourBoard,
            locked = uiState.fleetSubmitted || uiState.placementGenerating,
            onCellClick = onPlaceShip
        )
        BoardCard(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            title = "Поле соперника",
            board = uiState.enemyBoard,
            locked = !uiState.yourTurn || !uiState.fleetSubmitted,
            onCellClick = onAttack
        )
    }
}

@Composable
private fun BoardCard(
    modifier: Modifier,
    title: String,
    board: List<List<CellState>>,
    locked: Boolean,
    onCellClick: (Cell) -> Unit,
) {
    Surface(
        modifier = modifier,
        color = SeaWarTheme.surface,
        shape = RoundedCornerShape(18.dp),
        elevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .border(1.dp, SeaWarTheme.border, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Text(
                text = title,
                fontFamily = SeaWarTheme.bodyFont,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = SeaWarTheme.textPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            BoardWithAxis(
                board = board,
                locked = locked,
                onCellClick = onCellClick
            )
        }
    }
}

@Composable
private fun BoardWithAxis(
    board: List<List<CellState>>,
    locked: Boolean,
    onCellClick: (Cell) -> Unit,
) {
    Column {
        Row(modifier = Modifier.padding(start = 28.dp)) {
            for (x in 0 until BOARD_SIZE) {
                AxisLabel(text = ('A'.code + x).toChar().toString())
            }
        }
        Row {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                for (y in 1..BOARD_SIZE) {
                    AxisLabel(text = y.toString(), modifier = Modifier.height(34.dp))
                }
            }
            Column {
                for (y in 0 until BOARD_SIZE) {
                    Row {
                        for (x in 0 until BOARD_SIZE) {
                            val cell = Cell(x, y)
                            val state = board.getOrNull(y)?.getOrNull(x) ?: CellState.Unknown
                            BoardCell(
                                state = state,
                                locked = locked || state != CellState.Unknown,
                                onClick = { onCellClick(cell) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AxisLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.width(34.dp),
        textAlign = TextAlign.Center,
        fontFamily = SeaWarTheme.monoFont,
        fontSize = 12.sp,
        color = SeaWarTheme.textMuted
    )
}

@Composable
private fun BoardCell(state: CellState, locked: Boolean, onClick: () -> Unit) {
    val color = when (state) {
        CellState.Unknown -> SeaWarTheme.cellUnknown
        CellState.Miss -> SeaWarTheme.cellMiss
        CellState.Hit -> SeaWarTheme.cellHit
        CellState.Ship -> SeaWarTheme.cellShip
    }
    val symbol = when (state) {
        CellState.Hit -> "X"
        CellState.Miss -> "•"
        CellState.Ship -> "■"
        else -> ""
    }
    Box(
        modifier = Modifier
            .size(34.dp)
            .padding(1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .clickable(enabled = !locked, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            fontFamily = SeaWarTheme.monoFont,
            fontSize = 12.sp,
            color = SeaWarTheme.cellText
        )
    }
}