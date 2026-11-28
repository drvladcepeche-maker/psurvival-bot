package com.psurvival.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenshotCropBoundsTest {
    @Test fun acceptsBoundsFullyInsideDisplay() {
        val result = ScreenshotCropBounds.calculate(10, 20, 900, 1800, 1080, 2400)
        requireNotNull(result)
        assertEquals(10, result.left)
        assertEquals(20, result.top)
        assertEquals(890, result.width)
        assertEquals(1780, result.height)
    }

    @Test fun rejectsBoundsOutsideDisplayInsteadOfClamping() {
        assertNull(ScreenshotCropBounds.calculate(-1, 0, 900, 1800, 1080, 2400))
        assertNull(ScreenshotCropBounds.calculate(0, 0, 1081, 1800, 1080, 2400))
        assertNull(ScreenshotCropBounds.calculate(0, 0, 900, 2401, 1080, 2400))
    }

    @Test fun rejectsEmptyOrInvertedBounds() {
        assertNull(ScreenshotCropBounds.calculate(0, 0, 0, 10, 1080, 2400))
        assertNull(ScreenshotCropBounds.calculate(10, 10, 5, 20, 1080, 2400))
        assertNull(ScreenshotCropBounds.calculate(10, 20, 20, 20, 1080, 2400))
    }

    @Test fun rejectsInvalidImageDimensions() {
        assertNull(ScreenshotCropBounds.calculate(0, 0, 10, 10, 0, 2400))
        assertNull(ScreenshotCropBounds.calculate(0, 0, 10, 10, 1080, -1))
    }
}
