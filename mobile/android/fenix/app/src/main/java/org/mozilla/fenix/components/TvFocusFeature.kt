/* This Source Code Form is subject to the terms of the Mozilla Public
   - License, v. 2.0. If a copy of the MPL was not distributed with this
   - file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components

import mozilla.components.concept.engine.webextension.WebExtensionRuntime
import mozilla.components.support.base.log.logger.Logger

/**
 * Installs the built-in extension that draws a visible focus ring in web content.
 *
 * Android TV has no pointer: the remote moves DOM focus. Without a ring, a page with
 * no (or a very subtle) focus style looks frozen, so this is required for the browser
 * to be usable with a D-pad.
 */
object TvFocusFeature {
    private val logger = Logger("TvFocusFeature")

    internal const val TV_FOCUS_EXTENSION_ID = "tvfocus@mozac.org"
    internal const val TV_FOCUS_EXTENSION_URL = "resource://android/assets/extensions/tvfocus/"

    fun install(runtime: WebExtensionRuntime) {
        runtime.installBuiltInWebExtension(
            TV_FOCUS_EXTENSION_ID,
            TV_FOCUS_EXTENSION_URL,
            onSuccess = {
                logger.debug("Installed TV focus webextension: ${it.id}")
            },
            onError = { throwable ->
                logger.error("Failed to install TV focus webextension", throwable)
            },
        )
    }
}
