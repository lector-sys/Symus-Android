package io.github.lectorsys.symus.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterVersionTest {
    @Test fun comparesNumericallyAndIgnoresFlavour() {
        assertTrue(Updater.isNewer("v0.2.0", "0.1.0"))
        assertTrue(Updater.isNewer("v0.10.0", "0.9.9-lite"))
        assertTrue(Updater.isNewer("v1.0.1", "1.0"))
        assertFalse(Updater.isNewer("v0.1.0", "0.1.0-lite"))
        assertFalse(Updater.isNewer("v0.1.0", "0.2.0"))
        assertFalse(Updater.isNewer("v1.0", "1.0.0"))
    }
}
