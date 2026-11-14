// SPDX-FileCopyrightText: 2026 The TotalDrop Contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package app.totaldrop.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TvFloorPercentAndBridgeTest {
    @Test
    fun `floor percent never rounds 99_5 percent up to 100 percent early`() {
        val almostDone = computeFloorPercent(done = 995L, total = 1000L, regionLocale = Locale.US)
        assertEquals(99, almostDone.wholePercent)
        assertEquals("99%", almostDone.regionalLabel)

        val finished = computeFloorPercent(done = 1000L, total = 1000L, regionLocale = Locale.US)
        assertEquals(100, finished.wholePercent)
        assertEquals("100%", finished.regionalLabel)
    }

    @Test
    fun `floor percent follows the device region format`() {
        val tr = Locale.forLanguageTag("tr-TR")
        assertEquals("%50", computeFloorPercent(1L, 2L, tr).regionalLabel)
        assertEquals("50%", computeFloorPercent(1L, 2L, Locale.US).regionalLabel)
    }

    @Test
    fun `pairing URL keeps the transfer secret inside the URL fragment`() {
        val session = TvSendSession(
            handle = 1L,
            code = "7-harbor-rocket",
            items = 2L,
            totalBytes = 4096L,
        )
        assertEquals(listOf("7", "harbor", "rocket"), session.segments)
        assertEquals("https://totaldrop.app/r#7-harbor-rocket", session.pairingUrl)
        assertTrue(session.pairingUrl.contains("#7-harbor-rocket"))
    }
}
