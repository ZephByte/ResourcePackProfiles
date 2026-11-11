package org.zephbyte.resourcepackprofiles.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Small pixel icons defined as bitmaps ('#' = lit pixel) and drawn with fills, so they stay crisp
 * at every GUI scale and can be tinted freely.
 */
enum class Icon(vararg rows: String) {
    PLUS(
        "...#...",
        "...#...",
        "...#...",
        "#######",
        "...#...",
        "...#...",
        "...#..."
    ),
    CLOSE(
        "#.....#",
        ".#...#.",
        "..#.#..",
        "...#...",
        "..#.#..",
        ".#...#.",
        "#.....#"
    ),
    DOTS(
        "##.##.##",
        "##.##.##"
    ),
    GRIP(
        "#.#",
        "...",
        "#.#",
        "...",
        "#.#",
        "...",
        "#.#"
    ),
    STAR(
        "....#....",
        "....#....",
        "...###...",
        "#########",
        ".#######.",
        "..#####..",
        "..#####..",
        ".###.###.",
        ".#.....#."
    ),
    SEARCH(
        ".###....",
        "#...#...",
        "#...#...",
        "#...#...",
        ".###....",
        "....#...",
        ".....#..",
        "......#."
    ),
    REFRESH(
        "..####.#.",
        ".#....##.",
        "#....###.",
        "#........",
        "#.......#",
        "#.......#",
        ".#.....#.",
        "..#####.."
    ),
    CHEVRON(
        "#...#",
        ".#.#.",
        "..#.."
    ),
    FOLDER(
        ".####.......",
        "#....######.",
        "#..........#",
        "#..........#",
        "#..........#",
        "#..........#",
        "#..........#",
        "#..........#",
        ".##########."
    ),
    /** The "applied" marker: a bold check, 2px strokes so it reads at small GUI scales. */
    CHECK(
        ".......##",
        "......###",
        ".....###.",
        "##..###..",
        "######...",
        ".####....",
        "..##....."
    ),
    DUPLICATE(
        "######...",
        "#....#...",
        "#..######",
        "#..#....#",
        "#..#....#",
        "####....#",
        "...#....#",
        "...######"
    ),
    EXPORT(
        "....#....",
        "...###...",
        "..#.#.#..",
        "....#....",
        "....#....",
        "....#....",
        ".........",
        "#.......#",
        "#########"
    ),
    TRASH(
        "..####..",
        "########",
        "........",
        ".######.",
        ".#.##.#.",
        ".#.##.#.",
        ".#.##.#.",
        ".#.##.#.",
        "..####.."
    ),
    /** Small check drawn inside a tick box. */
    TICK(
        "....#",
        "...##",
        "#.##.",
        "###..",
        ".#..."
    ),
    IMPORT(
        "....#....",
        "....#....",
        "....#....",
        "..#.#.#..",
        "...###...",
        "....#....",
        ".........",
        "#.......#",
        "#########"
    );

    val width: Int = rows.maxOf { it.length }
    val height: Int = rows.size

    // Horizontal runs of lit pixels, precomputed so drawing is one fill per run.
    private val runs: List<IntArray> = buildList {
        rows.forEachIndexed { y, row ->
            var x = 0
            while (x < row.length) {
                if (row[x] == '#') {
                    val start = x
                    while (x < row.length && row[x] == '#') x++
                    add(intArrayOf(start, y, x))
                } else {
                    x++
                }
            }
        }
    }

    fun draw(context: GuiGraphicsExtractor, x: Int, y: Int, color: Int) {
        for (run in runs) context.fill(x + run[0], y + run[1], x + run[2], y + run[1] + 1, color)
    }

    /** Draws the icon centered in the given rectangle. */
    fun drawCentered(context: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, color: Int) =
        draw(context, x + (w - width) / 2, y + (h - height) / 2, color)
}
