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
import io.fabric8.openshift.api.model.Project
import io.fabric8.openshift.client.OpenShiftClient

class Projects(private val client: OpenShiftClient) {

    fun list(): List<Project> {
        return client.projects().list().items
    }

    /**
     * The namespaces Dev Spaces made for its users' workspaces that the user can see: their own, or every user's for a
     * cluster admin. Che labels them `app.kubernetes.io/component=workspaces-namespace` (and finds them by it), and
     * asking for the label spares a cluster admin two requests per namespace in the cluster. Empty on a cluster without
     * Dev Spaces, including one that isn't OpenShift.
     */
    fun listWorkspaceNamespaces(): List<Project> {
        return try {
            client.projects().withLabel(COMPONENT_LABEL, WORKSPACES_NAMESPACE).list().items
        } catch (e: KubernetesClientException) {
            // No project API: not OpenShift, so no Dev Spaces either
            if (e.code == 404) emptyList() else throw e
        }
    }

    companion object {
        const val COMPONENT_LABEL = "app.kubernetes.io/component"
        const val WORKSPACES_NAMESPACE = "workspaces-namespace"
    }
}