package hrln.interactive.sidekit.design

import hrln.interactive.sidekit.design.canvas.SidephoneCanvas
import hrln.interactive.sidekit.design.components.ListFade
import org.junit.Assert.assertEquals
import org.junit.Test

class ListFadeTest {
    private val pitch = 107 // 104 row + 3 item-outer-gap

    @Test fun `rows step down 0_2 per slot like the mocks`() {
        assertEquals(1.0f, ListFade.alpha(0, pitch), 0.001f)
        assertEquals(0.8f, ListFade.alpha(pitch, pitch), 0.001f)
        assertEquals(0.6f, ListFade.alpha(2 * pitch, pitch), 0.001f)
        assertEquals(0.4f, ListFade.alpha(3 * pitch, pitch), 0.001f)
    }

    @Test fun `alpha is continuous between slots while scrolling`() {
        assertEquals(0.9f, ListFade.alpha(pitch / 2, pitch), 0.01f)
    }

    @Test fun `rows scrolled above the top stay opaque and deep rows clamp`() {
        assertEquals(1f, ListFade.alpha(-50, pitch), 0f)
        assertEquals(ListFade.MIN_ALPHA, ListFade.alpha(20 * pitch, pitch), 0f)
    }
}

class SidephoneCanvasTest {
    @Test fun `sidephone panel maps one figma px to one physical px`() {
        assertEquals(1f, SidephoneCanvas.density(480, 640), 0f)
    }

    @Test fun `higher resolution panels scale the canvas uniformly`() {
        assertEquals(2f, SidephoneCanvas.density(960, 1280), 0f)
    }

    @Test fun `other aspect ratios letterbox to the smaller axis`() {
        // 1080 x 2400 phone: width limits (2.25) before height (3.75).
        assertEquals(2.25f, SidephoneCanvas.density(1080, 2400), 0f)
    }
}
