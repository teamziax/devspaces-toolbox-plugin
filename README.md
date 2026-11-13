# Red Hat OpenShift Dev Spaces (Eclipse Che) plugin for JetBrains Toolbox

[![Build](https://github.com/redhat-developer/devspaces-toolbox-plugin/workflows/Build/badge.svg)](https://github.com/redhat-developer/devspaces-toolbox-plugin/actions/workflows/build.yml)

Plugin for JetBrains Toolbox enables local desktop development experience with the IntelliJ IDEs connected to Red Hat OpenShift Dev Spaces.

<img width="486" height="746" alt="image" src="https://github.com/user-attachments/assets/84e1983c-2508-4318-a7a6-dc0d4e11d787" />

## Clusters and logins
The plugin finds workspaces through your kubeconfig (`KUBECONFIG`, or `~/.kube/config`), which `oc login` writes. It looks on every cluster that has a context there, not just the current context, so workspaces on two Dev Spaces clusters show up side by side, each labelled with its cluster. Switching `oc` contexts, or logging in to another cluster, doesn't change the list.

- Each workspace connects through the context it was found with.
- Besides the current context, it uses only contexts whose credentials are a token or a certificate, as `oc login` writes them. It never runs another context's credential plugin (`exec`), which might open a browser.
- It finds Dev Spaces' namespaces by Che's `app.kubernetes.io/component=workspaces-namespace` label. It asks a cluster without any every 5 minutes.
- When a login expires, the cluster's workspaces stay listed with a "Log in to … again" description. The plugin's menu gets a **Log in to …** action, which opens the cluster's page of `oc login` commands. `oc login --web <API server URL>` works too.
- When you open a workspace from the dashboard and the plugin can't find it, it asks every cluster again. If the workspace still isn't there, a popup says what each cluster answered.

## Developing
Edit-run loop:
1. Run `./gradlew installPlugin` to build the plugin and install it directly to Toolbox.
2. Restart the Toolbox to apply the installed plugin.

Alternatively, run `./gradlew :plugin:devReload` to build, install, and restart the Toolbox in a single step.
Add `-PskipToolboxRestart=true` to skip the restart, or `-PtoolboxExecutable=/path/to/jetbrains-toolbox` to point at a custom Toolbox executable.

To check the logs:
1. Go to the `About` page.
2. Click `Show log files`.
3. Open `toolbox.latest.log`.

## Release
- Find a draft release on the [Releases](https://github.com/redhat-developer/devspaces-toolbox-plugin/releases) page. The draft is created and updated automatically on each push to the `main` branch.
- Edit the draft:
  - Click the `Generate release notes` button and edit the release notes if needed
  - Click the `Publish release` button. The [Release](https://github.com/redhat-developer/devspaces-toolbox-plugin/blob/main/.github/workflows/release.yml) Workflow will attach the built plugin artifact to the published release and upload the plugin artifact to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/31372-red-hat-openshift-dev-spaces).
- Bump the `version` in the [gradle.properties](https://github.com/redhat-developer/devspaces-toolbox-plugin/blob/main/plugin/build.gradle.kts) file.
