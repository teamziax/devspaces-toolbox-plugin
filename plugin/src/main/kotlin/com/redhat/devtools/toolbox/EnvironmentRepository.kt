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
import com.jetbrains.toolbox.api.core.util.LoadableState
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.remoteDev.states.RemoteEnvironmentState
import com.redhat.devtools.toolbox.datasource.ClusterListing
import com.redhat.devtools.toolbox.datasource.EnvironmentDataSource
import com.redhat.devtools.toolbox.environment.*
import com.redhat.devtools.toolbox.openshift.ClusterContext
import com.redhat.devtools.toolbox.openshift.ClusterProblem
import com.redhat.devtools.toolbox.openshift.OpenShiftClientFactory
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What the plugin knows about one cluster in the kubeconfig.
 */
data class ClusterStatus(
    val cluster: ClusterContext,
    /** Whether the user has Dev Spaces namespaces there: null until the cluster answers, unless it's remembered. */
    val hostsDevSpaces: Boolean?,
    /** How many Toolbox-compatible workspaces it last listed. */
    val workspaces: Int,
    /** Why the cluster can't be used right now, if it can't. */
    val problem: ClusterProblem?,
)

/**
 * Repository managing the lifecycle of remote environments.
 *
 * Responsibilities:
 * - Fetches environment configs from data sources, from every cluster in the kubeconfig
 * - Creates/updates RemoteEnvironment instances
 * - Manages background polling
 * - Exposes reactive state for the provider
 */
class EnvironmentRepository(
    private val dataSource: EnvironmentDataSource,
    private val logger: Logger,
    private val coroutineScope: CoroutineScope,
    private val contentsViewFactory: EnvironmentContentsViewFactory = SshEnvironmentContentsViewFactory(),
    private val refreshInterval: Duration = 10.seconds,
    private val localizableStringFactory: LocalizableStringFactory,
    private val clientFactory: OpenShiftClientFactory,
    private val knownClusters: KnownDevSpacesClusters = KnownDevSpacesClusters(null, logger),
) {
    // Internal mutable state - holds the full unfiltered list
    private val _allEnvironments = MutableStateFlow<LoadableState<List<DevSpacesRemoteEnvironment>>>(
        LoadableState.Loading
    )

    // Cache of created environments by ID - allows updating existing instances
    private val environmentCache = ConcurrentHashMap<String, DevSpacesRemoteEnvironment>()

    // What each cluster last answered, by API server. Only used under refreshMutex.
    private val clusters = LinkedHashMap<String, ClusterEntry>()
    private val refreshMutex = Mutex()

    private val _clusterStatuses = MutableStateFlow<List<ClusterStatus>>(emptyList())

    /** Every cluster in the kubeconfig, in its order, and what it last answered. */
    val clusterStatuses: StateFlow<List<ClusterStatus>> = _clusterStatuses.asStateFlow()

    // When true, the environments list is filtered to show only the current user's workspaces
    val currentUserOnly = MutableStateFlow(true)

    // Filtered view exposed to the provider — reacts to both list updates and toggle changes
    val environments: StateFlow<LoadableState<List<DevSpacesRemoteEnvironment>>> =
        combine(_allEnvironments, currentUserOnly) { allEnvs, onlyMine ->
            if (!onlyMine) {
                allEnvs
            } else {
                allEnvs.map { list -> list.filter { it.belongsToViewer() } }
            }
        }.stateIn(coroutineScope, SharingStarted.Eagerly, LoadableState.Loading)

    fun startPolling() {
        coroutineScope.launch(CoroutineName("EnvironmentRepository-Polling")) {
            // Initial fetch
            refreshEnvironments()

            // periodically sync the workspaces list with the remote
            while (isActive) {
                delay(refreshInterval)
                refreshEnvironments()
            }
        }
    }

    /**
     * Triggers updating the environments list from the clusters that are due a check, or from all of them when
     * [force] is set.
     */
    suspend fun refreshEnvironments(force: Boolean = false) {
        refreshMutex.withLock { refreshLocked(force) }
    }

    /**
     * The environment with [id], after asking every cluster again if it isn't known yet. A dashboard link can arrive
     * before the first refresh is done (Toolbox starting is what opens it), or be for a cluster that's asked less
     * often.
     */
    suspend fun findEnvironment(id: String): DevSpacesRemoteEnvironment? {
        environmentCache[id]?.let { return it }
        return refreshMutex.withLock {
            environmentCache[id] ?: run {
                refreshLocked(force = true)
                environmentCache[id]
            }
        }
    }

    /** Whether "Show only my workspaces" hides [environment]. */
    fun isHiddenByFilter(environment: DevSpacesRemoteEnvironment): Boolean =
        currentUserOnly.value && !environment.belongsToViewer()

    /**
     * Why no cluster has the workspace with [id], for people: each cluster's state, and how to log in to another.
     */
    fun describeMissing(id: String): String {
        val loginHint = "If it runs on another cluster, log in to that one with:\n\noc login --web <its API server URL>\n\n" +
            "then open the workspace from the Dev Spaces dashboard again."
        val statuses = clusterStatuses.value
        if (statuses.isEmpty()) {
            return "Toolbox couldn't find workspace $id: your kubeconfig has no clusters. $loginHint"
        }
        val lines = statuses.joinToString("\n") { status ->
            val state = status.problem?.message ?: when {
                status.hostsDevSpaces != true -> "no Dev Spaces namespace you can see."
                status.workspaces == 0 -> "no workspaces Toolbox can open."
                status.workspaces == 1 -> "1 other workspace."
                else -> "${status.workspaces} other workspaces."
            }
            "• ${status.cluster.displayName}: $state"
        }
        return "Toolbox couldn't find workspace $id on the clusters you're logged in to:\n\n$lines\n\n$loginHint"
    }

    private suspend fun refreshLocked(force: Boolean) {
        logger.debug("Refreshing environments from ${dataSource::class.simpleName}")

        val kubeconfigClusters = try {
            dataSource.clusters()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Could not read the kubeconfig: ${e.message}")
            return
        }
        // Forget the clusters the kubeconfig no longer has a context for
        clusters.keys.retainAll(kubeconfigClusters.map { it.server }.toSet())

        val now = System.currentTimeMillis()
        val due = kubeconfigClusters.filter { cluster ->
            val entry = clusters[cluster.server]
            force || entry == null || entry.cluster != cluster || now >= entry.nextCheckAt
        }
        // In parallel, so a cluster that doesn't answer holds up only itself
        val outcomes = supervisorScope {
            due.map { cluster -> async { cluster to fetch(cluster) } }.awaitAll()
        }
        for ((cluster, outcome) in outcomes) {
            val entry = clusters.getOrPut(cluster.server) { ClusterEntry(cluster) }
            entry.cluster = cluster
            record(entry, outcome, now)
        }
        publish(kubeconfigClusters)
    }

    private suspend fun fetch(cluster: ClusterContext): Outcome {
        return try {
            Outcome.Listed(dataSource.fetchEnvironments(cluster))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val problem = when (val found = ClusterProblem.of(cluster, e)) {
                is ClusterProblem.LoginRequired -> found.copy(
                    tokenRequestUrl = withContext(Dispatchers.IO) { clientFactory.tokenRequestUrl(cluster) }
                )
                else -> found
            }
            Outcome.Failed(problem)
        }
    }

    private fun record(entry: ClusterEntry, outcome: Outcome, now: Long) {
        val cluster = entry.cluster
        when (outcome) {
            is Outcome.Listed -> {
                if (entry.problem != null) {
                    logger.info("${cluster.server} answers again")
                }
                val hostsDevSpaces = outcome.listing.hostsDevSpaces
                entry.environments = outcome.listing.environments
                entry.problem = null
                entry.hostsDevSpaces = hostsDevSpaces
                entry.answered = true
                if (hostsDevSpaces) {
                    knownClusters.remember(cluster.server)
                }
                entry.nextCheckAt = if (hostsDevSpaces) now else now + NO_DEVSPACES_RECHECK.inWholeMilliseconds
            }

            is Outcome.Failed -> {
                if (entry.problem != outcome.problem) {
                    logger.warn("${cluster.server}, through context ${cluster.context}: ${outcome.problem.message}")
                }
                // Its workspaces stay listed, marked with the problem, rather than vanishing
                entry.problem = outcome.problem
                if (entry.hostsDevSpaces == null && knownClusters.contains(cluster.server)) {
                    entry.hostsDevSpaces = true
                }
                // A Dev Spaces cluster is asked again soon, so an `oc login` shows up quickly
                val recheck = if (entry.hostsDevSpaces == true) FAILED_RECHECK else NO_DEVSPACES_RECHECK
                entry.nextCheckAt = now + recheck.inWholeMilliseconds
            }
        }
    }

    private fun publish(kubeconfigClusters: List<ClusterContext>) {
        val entries = kubeconfigClusters.mapNotNull { clusters[it.server] }
        val seen = HashSet<String>()
        val environments = entries
            .flatMap { entry -> entry.environments.map { config -> config to entry.problem } }
            // The same workspace, should two of the kubeconfig's servers be one cluster
            .filter { (config, _) -> seen.add(config.id) }
            .sortedWith(Comparator { a, b -> LIST_ORDER.compare(a.first, b.first) })
            .map { (config, problem) -> getOrCreateEnvironment(config).also { it.showProblem(problem) } }

        // Remove environments that no longer exist
        environmentCache.keys.retainAll(seen)

        // Until a cluster answers, the list is still loading, and the provider's loading text says how to log in.
        // That includes a kubeconfig with no contexts left.
        if (entries.any { it.answered }) {
            val ids = environments.map { it.id }
            if (_allEnvironments.value.takeIfValue()?.value?.map { it.id } != ids) {
                logger.info("PLUGIN: Setting environments to ${environments.size} items: $ids")
            }
            _allEnvironments.value = LoadableState.Value(environments)
        } else {
            _allEnvironments.value = LoadableState.Loading
        }
        _clusterStatuses.value = entries.map { ClusterStatus(it.cluster, it.hostsDevSpaces, it.environments.size, it.problem) }
    }

    /**
     * Gets an existing environment or creates a new one.
     * This preserves reactive subscriptions when refreshing.
     */
    private fun getOrCreateEnvironment(config: EnvironmentConfig): DevSpacesRemoteEnvironment {
        return environmentCache.getOrPut(config.id) {
            logger.debug("Creating new environment: ${config.id}")
            config.toRemoteEnvironment(contentsViewFactory, localizableStringFactory, logger, clientFactory)
        }.also { existingEnv ->
            // Update config if it is changed
            if (existingEnv.getConfig() != config) {
                logger.debug("Updating environment config: ${config.id}")
                existingEnv.updateConfig(config)
            }
        }
    }

    /**
     * Manually update a specific environment's state.
     * Useful for health check results, error reporting, etc.
     */
    fun updateEnvironmentState(
        environmentId: String, state: RemoteEnvironmentState, errorMessage: String? = null
    ) {
        environmentCache[environmentId]?.updateState(state, errorMessage)
            ?: logger.warn("Cannot update unknown environment: $environmentId")
    }

    /**
     * Allows to trigger programmatically local ThinClient connection to a remote environment.
     */
    fun updateConnectionRequest(
        environmentId: String, state: Boolean, errorMessage: String? = null
    ) {
        environmentCache[environmentId]?.updateConnectionRequest(state, errorMessage)
            ?: logger.warn("Cannot update connection request for unknown environment: $environmentId")
    }

    /**
     * Get a specific environment by ID.
     */
    fun getEnvironment(id: String): DevSpacesRemoteEnvironment? = environmentCache[id]

    private class ClusterEntry(var cluster: ClusterContext) {
        var environments: List<EnvironmentConfig> = emptyList()
        var problem: ClusterProblem? = null
        var hostsDevSpaces: Boolean? = null
        var answered = false
        var nextCheckAt = 0L
    }

    private sealed interface Outcome {
        data class Listed(val listing: ClusterListing) : Outcome
        data class Failed(val problem: ClusterProblem) : Outcome
    }

    private companion object {
        // A cluster without Dev Spaces namespaces is asked again this often; a dashboard link asks every cluster at once
        val NO_DEVSPACES_RECHECK = 5.minutes

        // A Dev Spaces cluster that failed is asked again this often
        val FAILED_RECHECK = 30.seconds

        // Owner (as develop sorted), then cluster, then name
        val LIST_ORDER: Comparator<EnvironmentConfig> =
            compareBy<EnvironmentConfig, String?>(nullsLast()) { it.tags["owner"] }
                .thenBy { it.cluster?.displayName }
                .thenBy { it.name.value }
    }
}
