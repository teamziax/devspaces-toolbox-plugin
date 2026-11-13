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
package com.redhat.devtools.toolbox.datasource

import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.redhat.devtools.toolbox.environment.EnvironmentConfig
import com.redhat.devtools.toolbox.openshift.ClusterContext
import com.redhat.devtools.toolbox.openshift.OpenShiftClientFactory
import com.redhat.devtools.toolbox.openshift.DevWorkspace
import com.redhat.devtools.toolbox.openshift.DevWorkspaces
import com.redhat.devtools.toolbox.openshift.Projects
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.openshift.client.OpenShiftClient
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Data source returns the environment configurations from
 * the DevWorkspaces on the Dev Spaces clusters the user is logged in to.
 */
class DevWorkspacesDataSource(
    private val clientFactory: OpenShiftClientFactory,
    private val logger: Logger
) : EnvironmentDataSource {

    // The OpenShift user each kubeconfig login is, by API server and kubeconfig user. Asked once per session.
    private val viewers = ConcurrentHashMap<String, String>()

    override fun clusters(): List<ClusterContext> = clientFactory.clusters()

    /**
     * Fetches the Toolbox-compatible DevWorkspaces in the Dev Spaces namespaces the user can see on [cluster].
     */
    override suspend fun fetchEnvironments(cluster: ClusterContext): ClusterListing = withContext(Dispatchers.IO) {
        try {
            clientFactory.create(cluster, LIST_CONNECTION_TIMEOUT_MILLIS, LIST_REQUEST_TIMEOUT_MILLIS, LIST_RETRIES).use { client ->
                val namespaces = Projects(client).listWorkspaceNamespaces()
                    .mapNotNull { project ->
                        val namespace = project.metadata?.name ?: return@mapNotNull null
                        val namespaceOwner = project.metadata?.annotations?.get(USERNAME_ANNOTATION)
                        namespace to namespaceOwner
                    }
                if (namespaces.isEmpty()) {
                    ClusterListing(emptyList(), hostsDevSpaces = false)
                } else {
                    val viewer = viewer(client, cluster)
                    val devWorkspaces = DevWorkspaces(client, logger)
                    val environments = namespaces.flatMap { (namespace, namespaceOwner) ->
                        devWorkspaces.list(namespace)
                            // DevWorkspace Operator gives a workspace its ID once it has seen it
                            .filter { it.id.isNotBlank() }
                            .map { workspace -> workspace.toConfig(cluster, viewer, workspace.owner ?: namespaceOwner) }
                    }
                    ClusterListing(environments, hostsDevSpaces = true)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("Failed to fetch environments from ${cluster.server}: ${e.message}")
            throw DataSourceException(e.message.toString(), e)
        }
    }

    private fun DevWorkspace.toConfig(cluster: ClusterContext, viewer: String?, owner: String?) = EnvironmentConfig(
        id = id,
        name = MutableStateFlow(name),
        description = phase,
        port = 2022, // the port of in-container running sshd
//        availableIdeProductCodes = listOf("IU"),
        // Expose every project the workspace clones so the user can open
        // any of them directly from Toolbox. Fall back to the projects
        // root for workspaces that declare no projects.
        projectPaths = projectPaths.ifEmpty {
            listOf(DevWorkspace.PROJECTS_ROOT)
        },
        tags = buildMap {
            put("namespace", namespace)
            if (owner != null) put("owner", owner)
        },
        cluster = cluster,
        viewer = viewer,
    )

    // The OpenShift user name, which Dev Spaces writes on the user's namespace as its owner
    private fun viewer(client: OpenShiftClient, cluster: ClusterContext): String? {
        val key = "${cluster.server} ${cluster.kubeconfigUser}"
        viewers[key]?.let { return it }
        return try {
            client.currentUser()?.metadata?.name?.also { viewers[key] = it }
        } catch (e: KubernetesClientException) {
            logger.warn("Could not resolve the current user on ${cluster.server}: ${e.message}")
            null
        }
    }

    private companion object {
        const val USERNAME_ANNOTATION = "che.eclipse.org/username"

        // Short, and one retry rather than fabric8's 10 (about 19 seconds), so a cluster that doesn't answer doesn't
        // hold up the list, or a dashboard link waiting for it. The next poll tries again anyway.
        const val LIST_CONNECTION_TIMEOUT_MILLIS = 5_000
        const val LIST_REQUEST_TIMEOUT_MILLIS = 15_000
        const val LIST_RETRIES = 1
    }
}
