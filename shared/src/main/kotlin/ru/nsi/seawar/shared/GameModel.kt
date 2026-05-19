package ru.nsi.seawar.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val BOARD_SIZE = 10

@Serializable
data class ShipPlacement(
    val cells: List<Cell>,
)

@Serializable
data class Cell(
    val x: Int,
    val y: Int,
)

@Serializable
enum class CellState {
    Unknown,
    Miss,
    Hit,
    Ship,
}

@Serializable
enum class Orientation {
    Horizontal,
    Vertical,
}

@Serializable
data class ShipDefinition(
    val size: Int,
)

val defaultFleet = listOf(
    ShipDefinition(5),
    ShipDefinition(4),
    ShipDefinition(3),
    ShipDefinition(3),
    ShipDefinition(2),
)

@Serializable
sealed class ClientMessage {
    @Serializable
    @SerialName("join")
    data class Join(val name: String) : ClientMessage()

    @Serializable
    @SerialName("placeFleet")
    data class PlaceFleet(val ships: List<ShipPlacement>) : ClientMessage()

    @Serializable
    @SerialName("attack")
    data class Attack(val cell: Cell) : ClientMessage()
}

@Serializable
sealed class ServerMessage {
    @Serializable
    @SerialName("welcome")
    data class Welcome(val playerId: String) : ServerMessage()

    @Serializable
    @SerialName("roomWaiting")
    data class RoomWaiting(val message: String) : ServerMessage()

    @Serializable
    @SerialName("gameReady")
    data class GameReady(val yourTurn: Boolean, val opponentName: String) : ServerMessage()

    @Serializable
    @SerialName("state")
    data class State(
        val yourBoard: BoardView,
        val enemyBoard: BoardView,
        val status: String,
        val yourTurn: Boolean,
        val winner: String? = null,
    ) : ServerMessage()

    @Serializable
    @SerialName("error")
    data class Error(val message: String) : ServerMessage()
}

@Serializable
data class BoardView(
    val cells: List<List<CellState>>,
)

fun emptyBoardView(): BoardView = BoardView(
    cells = List(BOARD_SIZE) { List(BOARD_SIZE) { CellState.Unknown } },
)

fun createBoardView(
    ships: Set<Cell>,
    shots: Map<Cell, CellState>,
    revealShips: Boolean,
): BoardView {
    val rows = List(BOARD_SIZE) { y ->
        List(BOARD_SIZE) { x ->
            val cell = Cell(x, y)
            when (shots[cell]) {
                CellState.Hit -> CellState.Hit
                CellState.Miss -> CellState.Miss
                else -> if (revealShips && cell in ships) CellState.Ship else CellState.Unknown
            }
        }
    }
    return BoardView(rows)
}

fun validateFleet(ships: List<ShipPlacement>): Result<Set<Cell>> {
    val allCells = linkedSetOf<Cell>()
    val cellToShip = mutableMapOf<Cell, Int>()
    val expectedSizes = defaultFleet.map { it.size }.sortedDescending()
    val actualSizes = ships.map { it.cells.size }.sortedDescending()
    if (expectedSizes != actualSizes) {
        return Result.failure(IllegalArgumentException("Нужно разместить флот: ${defaultFleet.joinToString { it.size.toString() }}"))
    }

    ships.forEachIndexed { index, ship ->
        if (ship.cells.isEmpty()) {
            return Result.failure(IllegalArgumentException("Корабль не может быть пустым"))
        }

        val xs = ship.cells.map { it.x }.distinct()
        val ys = ship.cells.map { it.y }.distinct()
        val alignedHorizontally = ys.size == 1 && xs.size == ship.cells.size
        val alignedVertically = xs.size == 1 && ys.size == ship.cells.size
        if (!alignedHorizontally && !alignedVertically) {
            return Result.failure(IllegalArgumentException("Корабль должен стоять по прямой"))
        }

        val sortedCells = if (alignedHorizontally) {
            ship.cells.sortedBy { it.x }
        } else {
            ship.cells.sortedBy { it.y }
        }

        sortedCells.zipWithNext().forEach { (current, next) ->
            if (kotlin.math.abs(current.x - next.x) + kotlin.math.abs(current.y - next.y) != 1) {
                return Result.failure(IllegalArgumentException("Корабль должен занимать соседние клетки"))
            }
        }

        for (cell in ship.cells) {
            if (cell.x !in 0 until BOARD_SIZE || cell.y !in 0 until BOARD_SIZE) {
                return Result.failure(IllegalArgumentException("Корабль выходит за пределы поля"))
            }
            if (cellToShip.containsKey(cell)) {
                return Result.failure(IllegalArgumentException("Корабли не могут пересекаться"))
            }
            cellToShip[cell] = index
            allCells.add(cell)
        }
    }

    for ((cell, shipIndex) in cellToShip) {
        for (neighbor in allCellsAround(cell)) {
            val neighborShip = cellToShip[neighbor]
            if (neighborShip != null && neighborShip != shipIndex) {
                return Result.failure(IllegalArgumentException("Корабли не должны касаться"))
            }
        }
    }

    return Result.success(allCells)
}

fun allCellsAround(cell: Cell): List<Cell> = buildList {
    for (dy in -1..1) {
        for (dx in -1..1) {
            val candidate = Cell(cell.x + dx, cell.y + dy)
            if (candidate.x in 0 until BOARD_SIZE && candidate.y in 0 until BOARD_SIZE) {
                add(candidate)
            }
        }
    }
}