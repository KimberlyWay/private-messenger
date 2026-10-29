package uk.mywisp.privatechat

import kotlin.math.min

/** Coordinates are in the oriented source image, independent of screen density. */
internal data class AvatarCrop(val left: Float, val top: Float, val side: Float)
internal fun avatarCrop(width: Int, height: Int, zoom: Float, x: Float, y: Float): AvatarCrop {
    require(width > 0 && height > 0)
    require(zoom.isFinite() && x.isFinite() && y.isFinite())
    val side = min(width, height).toFloat() / zoom.coerceIn(1f, 5f)
    val left = (width - side) * (x.coerceIn(-1f, 1f) + 1f) / 2f
    val top = (height - side) * (y.coerceIn(-1f, 1f) + 1f) / 2f
    return AvatarCrop(left, top, side)
}
