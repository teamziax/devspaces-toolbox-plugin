/*
 * Copyright (c) 2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.toolbox

import com.jetbrains.toolbox.api.core.diagnostics.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * The API servers on which the plugin has found Dev Spaces namespaces, remembered across Toolbox restarts in the
 * plugin's settings. When a login expires while Toolbox is closed, the plugin can still tell a Dev Spaces cluster that
 * needs a login from the other clusters in the kubeconfig.
 */
class KnownDevSpacesClusters(
    private val settings: MutableMap<String, String>?,
    private val logger: Logger,
) {
    private val servers: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
        settings?.get(KEY)?.split(',')?.filter { it.isNotBlank() }?.let { addAll(it) }
    }

    fun contains(server: String): Boolean = server in servers

    fun remember(server: String) {
        if (!servers.add(server)) return
        try {
            settings?.set(KEY, servers.sorted().joinToString(","))
        } catch (e: Exception) {
            logger.warn("Could not save the Dev Spaces clusters in the plugin's settings: ${e.message}")
        }
    }

    private companion object {
        const val KEY = "devSpacesClusters"
    }
}
