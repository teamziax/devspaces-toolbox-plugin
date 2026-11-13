# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

JetBrains Toolbox plugin that enables local IntelliJ IDEs to connect to Red Hat OpenShift Dev Spaces (Eclipse Che) workspaces via SSH.

## Build & Install Commands

```bash
# Build the plugin JAR
./gradlew build

# Build and install to JetBrains Toolbox
./gradlew installPlugin

# Clean build artifacts
./gradlew clean
```

After installing, restart the Toolbox App to load the plugin.

## Architecture

```
DataSource → EnvironmentConfig → Repository → RemoteEnvironment → Provider → Toolbox UI
```

**Key components in `plugin/src/main/kotlin/com/redhat/devtools/toolbox/`:**

- **DevSpacesRemoteDevExtension** (`DevSpacesPlugin.kt`): Entry point implementing `RemoteDevExtension`. Creates the provider and repository, wires up the data source.

- **DevSpacesRemoteProvider** (`DevSpacesRemoteProvider.kt`): `RemoteProvider` implementation. Handles URI callbacks (`jetbrains://gateway/com.redhat.devtools.toolbox?...`) from Dev Spaces Dashboard, triggering environment refresh and connection requests.

- **EnvironmentRepository** (`EnvironmentRepository.kt`): Manages environment lifecycle - fetches configs from data source, caches `RemoteEnvironment` instances, exposes reactive state via `MutableStateFlow`.

- **EnvironmentDataSource** (`datasource/`): Interface for fetching environment configs, one cluster at a time. `DevWorkspacesDataSource` is the real implementation.

- **OpenShiftClientFactory** (`openshift/`): Reads the kubeconfig's contexts (one per API server, without running credential plugins) and makes a client per context. The repository asks every cluster, so each `EnvironmentConfig` carries the `ClusterContext` it was found on, and connecting uses that context. Failures become a `ClusterProblem`, worded for people.

- **DevSpacesRemoteEnvironment** (`environment/`): Wraps `EnvironmentConfig` with Toolbox API reactive properties (state, description, connection requests).

- **SshEnvironmentContentsViewFactory** (`environment/`): Creates SSH connection views. Current limitation: hardcoded local port 2022, supports only one active connection.

## Technical Notes

- The `group` value in `plugin/build.gradle.kts` (`com.redhat.devtools.toolbox`) is the provider ID used in URI handling.
- Plugin requires Java 21 (configured via `jvmToolchain(21)`).
- Uses JetBrains Toolbox Plugin API version defined in `gradle/libs.versions.toml`.
- The repository polls every 10 seconds. A cluster without Dev Spaces namespaces is asked every 5 minutes, and a failing Dev Spaces cluster every 30 seconds. A `jetbrains://` link for a workspace it doesn't know makes it ask every cluster at once.