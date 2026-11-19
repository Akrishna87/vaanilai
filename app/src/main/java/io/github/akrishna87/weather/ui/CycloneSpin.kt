package io.github.akrishna87.weather.ui

private const val CYCLONE_REVOLUTION_MS = 3_600L

/** Screen y increases downwards, so negative angles rotate anticlockwise. */
internal fun cycloneSpinDirection(latitude: Double): Int = if (latitude >= 0) -1 else 1

internal fun cycloneSpinPhase(frameMs: Long, direction: Int): Double =
    direction * (frameMs % CYCLONE_REVOLUTION_MS) / CYCLONE_REVOLUTION_MS.toDouble() * (2 * Math.PI)
