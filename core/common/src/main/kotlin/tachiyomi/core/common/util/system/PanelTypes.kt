package tachiyomi.core.common.util.system

import android.graphics.Rect

data class Panel(val rect: Rect)

enum class ReadingDirection {
    RTL,
    LTR,
    VERTICAL,
}

fun Rect.flattenToString(): String = "($left,$top,$right,$bottom)"