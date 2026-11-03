/* This Source Code Form is subject to the terms of the Mozilla Public
   - License, v. 2.0. If a copy of the MPL was not distributed with this
   - file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components

import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.preferences.Branch
import mozilla.components.ExperimentalAndroidComponentsApi
import mozilla.components.support.base.log.logger.Logger

/**
 * Gecko tuning for low-end Android TV hardware (Amlogic S905, Mali-G31, 2-3 GB RAM,
 * 32-bit userspace).
 *
 * These are the knobs that actually change behaviour on this class of device:
 *
 * - A single content process. Three tabs is the realistic ceiling on a TV, and each
 *   extra Gecko content process costs tens of MB plus a zygote fork. Fewer processes
 *   also means less allocator churn on a 32-bit address space.
 * Preferences that need a restart (process count) take effect on the next launch.
 */
@OptIn(ExperimentalAndroidComponentsApi::class)
object TvPerformanceFeature {
    private val logger = Logger("TvPerformanceFeature")

    fun apply(engine: Engine) {
        val onError: (String, Throwable) -> Unit = { pref, throwable ->
            logger.error("Failed to set $pref", throwable)
        }

        engine.setBrowserPref(
            pref = "dom.ipc.processCount",
            value = 1,
            branch = Branch.USER,
            onSuccess = {},
            onError = { onError("dom.ipc.processCount", it) },
        )

        engine.setBrowserPref(
            pref = "browser.tabs.unloadOnLowMemory",
            value = true,
            branch = Branch.USER,
            onSuccess = {},
            onError = { onError("browser.tabs.unloadOnLowMemory", it) },
        )
    }
}
