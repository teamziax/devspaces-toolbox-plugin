package com.redhat.devtools.toolbox.openshift

import com.jetbrains.toolbox.api.core.diagnostics.Logger
import io.fabric8.kubernetes.api.model.AuthInfo
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.utils.KubernetesSerialization
import io.fabric8.openshift.client.OpenShiftClient
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import io.fabric8.kubernetes.api.model.Config as Kubeconfig

/**
 * Makes clients for the clusters in the user's kubeconfig (`KUBECONFIG`, or `~/.kube/config`), which `oc login`
 * writes.
 */
class OpenShiftClientFactory(
    private val logger: Logger
) {
    private val serialization = KubernetesSerialization()
    private val tokenRequestUrls = ConcurrentHashMap<String, Optional<String>>()

    /**
     * The clusters the kubeconfig has a context for, one per API server: the current context for its server, else the
     * kubeconfig's first context for it. `oc login` makes a context per project, all with the same login, so one is
     * enough.
     *
     * This reads the kubeconfig's files without resolving any credentials. Resolving a context runs its credential
     * plugin (`exec`), which may open a browser to log in, so contexts other than the current one are only used when
     * their credentials are a token or a certificate, as `oc login` writes them.
     */
    fun clusters(): List<ClusterContext> {
        val kubeconfigs = Config.getKubeconfigFilenames().map { File(it) }.filter { it.isFile }.mapNotNull { parse(it) }
        // As kubectl merges KUBECONFIG's files: the first file to set a name, or the current context, wins
        val current = kubeconfigs.firstNotNullOfOrNull { kubeconfig -> kubeconfig.currentContext?.takeIf { it.isNotBlank() } }
        val contexts = firstByName(kubeconfigs.flatMap { it.contexts.orEmpty() }) { it.name }
        val servers = firstByName(kubeconfigs.flatMap { it.clusters.orEmpty() }) { it.name }
        val users = firstByName(kubeconfigs.flatMap { it.users.orEmpty() }) { it.name }

        val clusters = LinkedHashMap<String, ClusterContext>()
        for (named in contexts.values.sortedByDescending { it.name == current }) {
            val name = named.name ?: continue
            val context = named.context ?: continue
            val server = servers[context.cluster]?.cluster?.server?.let { ClusterContext.normalizeServer(it) } ?: continue
            if (name != current && users[context.user]?.user?.runsAProgram() == true) {
                continue
            }
            clusters.putIfAbsent(server, ClusterContext(name, server, context.user))
        }
        return clusters.values.toList()
    }

    /**
     * A client for [cluster], through its context. The client reads the context's token from the kubeconfig again
     * when it's refused, so it keeps working across `oc login`s. Null options keep fabric8's defaults, which retry a
     * failed request 10 times over about 19 seconds.
     *
     * @throws ClusterContextGoneException when the kubeconfig no longer has the context, or it points elsewhere
     */
    fun create(
        cluster: ClusterContext,
        connectionTimeoutMillis: Int? = null,
        requestTimeoutMillis: Int? = null,
        retries: Int? = null,
    ): OpenShiftClient {
        // autoConfigure falls back to the current context when there's no context by that name
        val config = Config.autoConfigure(cluster.context)
        val server = config.masterUrl?.let { ClusterContext.normalizeServer(it) }
        if (config.currentContext?.name != cluster.context || server != cluster.server) {
            throw ClusterContextGoneException(cluster)
        }
        if (connectionTimeoutMillis != null) {
            config.setConnectionTimeout(connectionTimeoutMillis)
        }
        if (requestTimeoutMillis != null) {
            config.setRequestTimeout(requestTimeoutMillis)
        }
        if (retries != null) {
            config.setRequestRetryBackoffLimit(retries)
        }
        return try {
            KubernetesClientBuilder().withConfig(config).build().adapt(OpenShiftClient::class.java)
        } catch (e: Exception) {
            logger.debug("Failed to build OpenShift client for context ${cluster.context}: ${e.message}")
            throw e
        }
    }

    /**
     * The page on [cluster]'s OpenShift OAuth server that hands out `oc login` commands, from the API server's OAuth
     * metadata, which needs no login. Null when the cluster isn't OpenShift, or the JVM doesn't trust its certificate
     * by default. Looked up once per API server.
     */
    fun tokenRequestUrl(cluster: ClusterContext): String? {
        tokenRequestUrls[cluster.server]?.let { return it.orElse(null) }
        val url = lookUpTokenRequestUrl(cluster.server)
        tokenRequestUrls[cluster.server] = Optional.ofNullable(url)
        return url
    }

    // HttpURLConnection, not java.net.http's HttpClient: Toolbox's Java runtime has only some of the JDK's modules,
    // and java.net.http isn't one of them
    private fun lookUpTokenRequestUrl(server: String): String? {
        return try {
            val connection = URI.create("$server/.well-known/oauth-authorization-server").toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    null
                } else {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    ISSUER.find(body)?.groupValues?.get(1)?.trimEnd('/')?.let { "$it/oauth/token/request" }
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            logger.debug("No OAuth metadata from $server: ${e.message}")
            null
        }
    }

    private fun parse(file: File): Kubeconfig? {
        return try {
            file.inputStream().use { serialization.unmarshal(it, Kubeconfig::class.java) }
        } catch (e: Exception) {
            logger.warn("Could not read the kubeconfig $file: ${e.message}")
            null
        }
    }

    private fun <T> firstByName(items: List<T>, name: (T) -> String?): Map<String, T> {
        val byName = LinkedHashMap<String, T>()
        for (item in items) {
            val key = name(item) ?: continue
            byName.putIfAbsent(key, item)
        }
        return byName
    }

    // A credential plugin, or an auth provider that refreshes its own tokens
    private fun AuthInfo.runsAProgram(): Boolean = exec != null || authProvider != null

    private companion object {
        val ISSUER = Regex("\"issuer\"\\s*:\\s*\"([^\"]+)\"")
    }
}

/** The kubeconfig no longer has [cluster]'s context, or the context points at another API server now. */
class ClusterContextGoneException(val cluster: ClusterContext) :
    IllegalStateException("The kubeconfig context ${cluster.context} no longer points at ${cluster.server}")
