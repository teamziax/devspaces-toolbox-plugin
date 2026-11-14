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
package com.redhat.devtools.toolbox.openshift

import java.net.URI

/**
 * A cluster the user is logged in to: the kubeconfig context the plugin reaches it through, and that context's API
 * server. The plugin keeps one per API server ([OpenShiftClientFactory.clusters]).
 */
data class ClusterContext(
    /** The kubeconfig context's name. */
    val context: String,
    /** The API server's URL as the kubeconfig has it, without a trailing slash. */
    val server: String,
    /** The name of the context's credentials in the kubeconfig (not the OpenShift user name). */
    val kubeconfigUser: String? = null,
) {
    /** A short name for people: the API server's host without OpenShift's `api.` prefix, e.g. `dev1.vin1.ziax.ltd`. */
    val displayName: String
        get() = (runCatching { URI(server).host }.getOrNull() ?: server).removePrefix("api.")

    companion object {
        fun normalizeServer(url: String): String = url.trim().trimEnd('/')
    }
}
