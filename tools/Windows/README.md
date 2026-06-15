# dw - DevWorkspace shell helper

Exec an interactive bash shell into the `tools` container of a running
OpenShift DevWorkspace.

```powershell
dw rocket-omni
```

## Install

From this folder, in PowerShell:

```powershell
.\Install.ps1
```

That copies the module into your per-user PowerShell module path. Open a
**new** terminal and `dw` just works - no dot-sourcing, no profile editing.
PowerShell auto-loads the module the first time you call `dw`.

If PowerShell blocks the installer:

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
Unblock-File .\Install.ps1
.\Install.ps1
```

## Namespace

The namespace is **auto-detected** - the helper finds the single namespace
containing your DevWorkspaces, so most users never set anything.

If you have access to more than one such namespace (or auto-detect fails),
pin one explicitly. For the current session only:

```powershell
$env:DW_NAMESPACE = "devspaces-aroze-ziax-com-ck1n1f"
```

### Setting it permanently

**Windows-level (all programs - PowerShell, CMD, VS Code):**

```powershell
[Environment]::SetEnvironmentVariable("DW_NAMESPACE", "devspaces-aroze-ziax-com-ck1n1f", "User")
```

This persists for your Windows user account. It does **not** affect the
current window - open a new terminal (or set `$env:DW_NAMESPACE` once to
cover the current one).

**PowerShell profile (PowerShell only):**

```powershell
notepad $PROFILE.CurrentUserAllHosts   # create it first if needed:
                                       # New-Item -ItemType File -Path $PROFILE.CurrentUserAllHosts -Force
```

Add this line and save:

```powershell
$env:DW_NAMESPACE = "devspaces-aroze-ziax-com-ck1n1f"
```

**At install time:**

```powershell
.\Install.ps1 -Namespace "devspaces-aroze-ziax-com-ck1n1f"
```

## Tab completion

`dw <TAB>` lists workspaces that currently have a running pod.

## Optional env vars

| Variable          | Purpose                                  | Default |
|-------------------|------------------------------------------|---------|
| `DW_NAMESPACE`    | Pin a namespace (skips auto-detect)      | (auto)  |
| `DW_CONTAINER`    | Container to exec into                    | `tools` |
| `DW_DEBUG`        | Set to `1` for diagnostic output          | off     |

## Requirements

- `oc` (OpenShift CLI) on your PATH
- A logged-in cluster context (`oc login ...`)

## Uninstall

```powershell
Remove-Item -Recurse "$([Environment]::GetFolderPath('MyDocuments'))\PowerShell\Modules\dw"
```
(Use `WindowsPowerShell` instead of `PowerShell` if you're on Windows PowerShell 5.1.)
