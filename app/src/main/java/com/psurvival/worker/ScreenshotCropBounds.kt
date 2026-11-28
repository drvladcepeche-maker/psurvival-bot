package com.psurvival.worker

/**
 * Pure pixel-coordinate validation for screenshot cropping.
 * Returns null instead of clamping: partial/out-of-display windows must fail closed.
 */
internal object ScreenshotCropBounds {
    data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    fun calculate(
        left: Int, top: Int, right: Int, bottom: Int,
        imageWidth: Int, imageHeight: Int
    ): PixelRect? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        if (left < 0 || top < 0 || right <= left || bottom <= top) return null
        if (right > imageWidth || bottom > imageHeight) return null
        return PixelRect(left, top, right, bottom)
    }
}
