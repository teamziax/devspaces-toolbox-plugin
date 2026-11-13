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
import com.jetbrains.toolbox.api.core.os.LocalDesktopManager
import com.jetbrains.toolbox.api.core.util.LoadableState
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.remoteDev.ProviderVisibilityState
import com.jetbrains.toolbox.api.remoteDev.RemoteProvider
import com.jetbrains.toolbox.api.ui.ToolboxUi
import com.jetbrains.toolbox.api.ui.actions.ActionDescription
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.UiPage
import com.jetbrains.toolbox.platform.image.ImageResource
import com.jetbrains.toolbox.platform.image.image
import com.jetbrains.toolbox.platform.resource.jvm.jvmResourceReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.redhat.devtools.toolbox.environment.DevSpacesRemoteEnvironment
import com.redhat.devtools.toolbox.openshift.ClusterProblem
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

/**
 * [RemoteProvider] implementation that delegates the environment management to [EnvironmentRepository].
 */
class DevSpacesRemoteProvider(
    val repository: EnvironmentRepository,
    val localizableStringFactory: LocalizableStringFactory,
    val logger: Logger,
    val coroutineScope: CoroutineScope,
    private val ui: ToolboxUi,
    private val desktop: LocalDesktopManager,
) : RemoteProvider("Dev Spaces") {

    override val iconResource: ImageResource = jvmResourceReader().image("/icon.svg")

    override val noEnvironmentsDescription: String = "No DevWorkspaces found. Create a new one from the Dev Spaces Dashboard"
    override val loadingEnvironmentsDescription: LocalizableString = localizableStringFactory.ptrl("Loading the Workspaces list...\r\n\r\n" +
            "Toolbox looks for workspaces on every cluster\r\n" +
            "you're logged in to with 'oc login'.\r\n" +
            "If the list does not load within a few seconds -\r\n" +
            "log in to the cluster your Dev Spaces runs on\r\n" +
            "by running 'oc login --web <its API server URL>' in the terminal.")

    override val environments: StateFlow<LoadableState<List<DevSpacesRemoteEnvironment>>> =
        repository.environments

    // The filter toggle, and a "Log in" action for each Dev Spaces cluster whose login is missing or expired
    override val additionalPluginActions: StateFlow<List<ActionDescription>> =
        combine(repository.currentUserOnly, repository.clusterStatuses) { onlyMine, statuses ->
            val logins = statuses
                .filter { it.hostsDevSpaces == true }
                .mapNotNull { it.problem as? ClusterProblem.LoginRequired }
                .map { loginAction(it) }
            listOf(filterAction(onlyMine)) + logins
        }.stateIn(coroutineScope, SharingStarted.Eagerly, emptyList())

    override val canCreateNewEnvironments: Boolean = false
    override val isSingleEnvironment: Boolean = false

    override fun setVisible(visibilityState: ProviderVisibilityState) {}

    override fun getNewEnvironmentUiPage(): UiPage = UiPage(localizableStringFactory.pnotr("Choose a Workspace to connect to"))

    /**
     * Asks every cluster again now, rather than on the next poll: after an `oc login`, say.
     */
    override fun refresh() {
        coroutineScope.launch { repository.refreshEnvironments(force = true) }
    }

    /**
     * Handles an external request, typically comes from Che/DevSpaces Dashboard via the link as:
     *      jetbrains://gateway/com.redhat.devtools.toolbox?...
     *
     * 'jetbrainsd' local service handles such links by intercepting them and forwarding to the Toolbox.
     */
    override suspend fun handleUri(uri: URI) {
        logger.info("DevSpacesRemoteProvider received URI: $uri")

        val queryParams = uri.query?.split("&")?.associate { param ->
            val parts = param.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        } ?: emptyMap()

        val dwID: String = queryParams["dwID"] ?: ""

        if (dwID.isBlank()) {
            logger.warn("Received URI without valid dwID parameter: $uri")
            return
        }

        // The link names the workspace by ID alone, not its cluster: look on every cluster in the kubeconfig
        val environment = repository.findEnvironment(dwID)
        if (environment == null) {
            logger.warn("No cluster in the kubeconfig has a workspace with ID $dwID")
            showPopup("Workspace not found", repository.describeMissing(dwID))
            return
        }
        if (repository.isHiddenByFilter(environment)) {
            logger.info("Showing all workspaces: $dwID isn't the current user's")
            repository.currentUserOnly.value = false
        }

        // Schedule establishing the connection to the environment.
        environment.updateConnectionRequest(true)
    }

    override fun close() {}

    private fun filterAction(onlyMine: Boolean): ActionDescription = object : RunnableActionDescription {
        override val label = localizableStringFactory.pnotr(
            if (onlyMine) "Show all workspaces" else "Show only my workspaces"
        )
        override fun run() {
            repository.currentUserOnly.value = !repository.currentUserOnly.value
        }
    }

    // Opens the cluster's page of `oc login` commands, or says how to log in when the cluster doesn't publish one
    private fun loginAction(problem: ClusterProblem.LoginRequired): ActionDescription = object : RunnableActionDescription {
        override val label = localizableStringFactory.pnotr("Log in to ${problem.cluster.displayName}…")
        override fun run() {
            val url = problem.tokenRequestUrl
            if (url != null) {
                try {
                    desktop.openUrl(URI(url).toURL())
                    return
                } catch (e: Exception) {
                    logger.warn("Could not open $url: ${e.message}")
                }
            }
            showPopup("Log in to ${problem.cluster.displayName}", problem.message)
        }
    }

    // Fire and forget, as Coder's Toolbox plugin does: a popup waits for the Toolbox window to show, and isn't dropped
    // when there are several, as a snackbar can be
    private fun showPopup(title: String, text: String) {
        coroutineScope.launch {
            try {
                ui.showInfoPopup(
                    localizableStringFactory.pnotr(title),
                    localizableStringFactory.pnotr(text),
                    localizableStringFactory.ptrl("OK")
                )
            } catch (e: CancellationException) {
                // The popup closes with the plugin
            } catch (e: Exception) {
                logger.error("Could not show the popup \"$title\": ${e.message}")
            }
        }
    }
}
