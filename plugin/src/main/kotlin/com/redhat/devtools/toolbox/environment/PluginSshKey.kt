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
package com.redhat.devtools.toolbox.environment

import com.jetbrains.toolbox.api.core.diagnostics.Logger
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.UUID

/**
 * The key the plugin logs in to a workspace with when the workspace doesn't generate one.
 *
 * Dev Spaces' sshd sidecar (che-code-sshd) stopped generating a client key in late 2026. It lets the workspace user in
 * with an empty password instead, and some images' sshd refuse that: the dev-tools image runs Fedora's sshd-session
 * with `UsePAM no`, and that build can't check a password without PAM, so Toolbox asked for a password that doesn't
 * exist. A key works with any sshd.
 *
 * The private key stays in Toolbox; a new one is made for each Toolbox session. The workspace gets the public key in
 * its user's authorized_keys, marked with [comment], which replaces the key this Toolbox installed before.
 */
class PluginSshKey(
    private val settings: MutableMap<String, String>?,
    private val logger: Logger,
) {
    /** Marks this Toolbox's line in a workspace's authorized_keys. */
    val comment: String by lazy { "devspaces-toolbox-plugin:${installationId()}" }

    private val keyPair: KeyPair by lazy {
        KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
    }

    /** The private key: PKCS#8 in PEM, which OpenSSH reads. */
    val privateKeyPem: String
        get() = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"

    /** The public key, as a line of authorized_keys. */
    val authorizedKey: String
        get() {
            val key = keyPair.public as RSAPublicKey
            // SSH's wire format: the key type, then e and n as mpints, which BigInteger.toByteArray() already is
            val blob = ByteArrayOutputStream()
            DataOutputStream(blob).use { out ->
                for (part in listOf("ssh-rsa".toByteArray(), key.publicExponent.toByteArray(), key.modulus.toByteArray())) {
                    out.writeInt(part.size)
                    out.write(part)
                }
            }
            return "ssh-rsa ${Base64.getEncoder().encodeToString(blob.toByteArray())} $comment"
        }

    // Stable for this Toolbox installation, so each session's key replaces the last one rather than piling up
    private fun installationId(): String {
        settings?.get(INSTALLATION_ID)?.let { return it }
        val id = UUID.randomUUID().toString()
        try {
            settings?.set(INSTALLATION_ID, id)
        } catch (e: Exception) {
            logger.warn("Could not save the plugin's installation ID in its settings: ${e.message}")
        }
        return id
    }

    private companion object {
        const val INSTALLATION_ID = "installationId"
    }
}
