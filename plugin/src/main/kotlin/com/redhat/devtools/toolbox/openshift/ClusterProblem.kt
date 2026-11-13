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

import io.fabric8.kubernetes.client.KubernetesClientException
import java.io.IOException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLException

/**
 * Why the plugin can't use a cluster right now, worded for people: what went wrong, and what to do about it.
 */
sealed class ClusterProblem {
    abstract val cluster: ClusterContext

    /** A few words for a workspace's description in the list. */
    abstract val summary: String

    /** What went wrong, and what to do about it. */
    abstract val message: String

    /** The API server answered 401: the kubeconfig has no token for it, or the token has expired. */
    data class LoginRequired(
        override val cluster: ClusterContext,
        /** The OpenShift OAuth server's page that hands out `oc login` commands, when the cluster says where it is. */
        val tokenRequestUrl: String? = null,
    ) : ClusterProblem() {
        override val summary: String
            get() = "Log in to ${cluster.displayName} again"
        override val message: String
            get() = buildString {
                append("Your OpenShift login to ${cluster.displayName} is missing or has expired. ")
                append("Log in again with:\n\noc login --web ${cluster.server}")
                if (tokenRequestUrl != null) {
                    append("\n\nor with the command from $tokenRequestUrl")
                }
            }
    }

    /** The JVM doesn't trust the API server's certificate. */
    data class Untrusted(override val cluster: ClusterContext, val detail: String) : ClusterProblem() {
        override val summary: String
            get() = "Certificate of ${cluster.displayName} isn't trusted"
        override val message: String
            get() = "Toolbox doesn't trust the certificate of ${cluster.server} ($detail). If the cluster uses a " +
                "private CA, the cluster's entry in your kubeconfig needs its certificate-authority-data."
    }

    /** The API server didn't answer: DNS, a refused or timed-out connection, or one that dropped. */
    data class Unreachable(override val cluster: ClusterContext, val detail: String) : ClusterProblem() {
        override val summary: String
            get() = "Can't reach ${cluster.displayName}"
        override val message: String
            get() = "Toolbox can't reach ${cluster.server} ($detail)."
    }

    /** The kubeconfig no longer has the context, or the context points at another API server now. */
    data class ContextGone(override val cluster: ClusterContext) : ClusterProblem() {
        override val summary: String
            get() = "Kubeconfig context ${cluster.context} changed"
        override val message: String
            get() = "Your kubeconfig's context ${cluster.context} no longer points at ${cluster.server}. " +
                "Refresh the workspaces list, then try again."
    }

    /** Anything else, in the API server's or the client's own words. */
    data class Failed(override val cluster: ClusterContext, val detail: String) : ClusterProblem() {
        override val summary: String
            get() = "Can't list the workspaces on ${cluster.displayName}"
        override val message: String
            get() = "Toolbox couldn't list the workspaces on ${cluster.displayName}: $detail"
    }

    companion object {
        /**
         * Classifies [error], from talking to [cluster]. A [LoginRequired] has no [LoginRequired.tokenRequestUrl] yet:
         * finding it takes a request ([OpenShiftClientFactory.tokenRequestUrl]).
         */
        fun of(cluster: ClusterContext, error: Throwable): ClusterProblem {
            val causes = generateSequence(error) { it.cause }.take(16).toList()
            val root = causes.last()
            val detail = root.message?.takeIf { it.isNotBlank() } ?: root.javaClass.simpleName
            val status = causes.filterIsInstance<KubernetesClientException>().map { it.code }.firstOrNull { it > 0 }
            return when {
                causes.any { it is ClusterContextGoneException } -> ContextGone(cluster)
                status == 401 -> LoginRequired(cluster)
                status != null -> Failed(cluster, error.message ?: detail)
                // SSLException is an IOException, so before Unreachable
                causes.any { it is SSLException || it is GeneralSecurityException } -> Untrusted(cluster, detail)
                // Vert.x's timeouts aren't IOExceptions
                causes.any { it is IOException || it.javaClass.name.contains("Timeout") } -> Unreachable(cluster, detail)
                else -> Failed(cluster, error.message ?: detail)
            }
        }
    }
}
