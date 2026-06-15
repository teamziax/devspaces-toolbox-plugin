# dw - DevWorkspace shell helper (PowerShell module)
# ---------------------------------------------------
# Exports:
#   dw <workspace-name>   Exec an interactive bash shell into the `tools`
#                         container of a running DevWorkspace.
#
# Namespace resolution (first match wins):
#   1. $env:DW_NAMESPACE if set
#   2. Auto-detected: the single namespace containing your DevWorkspaces
#
# Optional env vars:
#   $env:DW_NAMESPACE   Pin a namespace explicitly (skips auto-detect)
#   $env:DW_CONTAINER   Container to exec into (default: tools)
#   $env:DW_DEBUG       Set to "1" for diagnostic output
# ---------------------------------------------------

# Cache so we don't re-query the cluster on every call / tab-press.
$script:DwNamespaceCache = $null

function Write-DwDebug {
    param([string]$Message)
    if (-not [string]::IsNullOrEmpty($env:DW_DEBUG)) {
        Write-Host "dw[debug]: $Message" -ForegroundColor DarkCyan
    }
}

function Test-DwPrereqs {
    if (-not (Get-Command oc -ErrorAction SilentlyContinue)) {
        Write-Error "dw: 'oc' not found on PATH. Install the OpenShift CLI or add it to PATH."
        return $false
    }
    return $true
}

function Resolve-DwNamespace {
    [CmdletBinding()]
    param([switch]$Refresh)

    # 1. Explicit override always wins.
    if (-not [string]::IsNullOrEmpty($env:DW_NAMESPACE)) {
        Write-DwDebug "using DW_NAMESPACE override: $env:DW_NAMESPACE"
        return $env:DW_NAMESPACE
    }

    # 2. Cached auto-detected value.
    if (-not $Refresh -and -not [string]::IsNullOrEmpty($script:DwNamespaceCache)) {
        Write-DwDebug "using cached namespace: $script:DwNamespaceCache"
        return $script:DwNamespaceCache
    }

    # 3. Auto-detect: find namespaces that actually contain DevWorkspaces.
    Write-DwDebug "auto-detecting namespace via 'oc get devworkspaces --all-namespaces'"
    $raw = oc get devworkspaces --all-namespaces `
        -o "jsonpath={range .items[*]}{.metadata.namespace}{'\n'}{end}" 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-DwDebug "auto-detect query failed (exit $LASTEXITCODE)"
        return $null
    }

    $namespaces = $raw -split "`n" | Where-Object { $_ } | Sort-Object -Unique

    if ($namespaces.Count -eq 1) {
        $script:DwNamespaceCache = $namespaces[0]
        Write-DwDebug "auto-detected single namespace: $($namespaces[0])"
        return $namespaces[0]
    }
    elseif ($namespaces.Count -gt 1) {
        Write-Error ("dw: found {0} namespaces with DevWorkspaces ({1}). " +
            "Set `$env:DW_NAMESPACE to pick one." -f $namespaces.Count, ($namespaces -join ", "))
        return $null
    }
    else {
        Write-DwDebug "no namespaces with DevWorkspaces found"
        return $null
    }
}

function dw {
    [CmdletBinding()]
    param(
        [Parameter(Position = 0)]
        [string]$ws
    )

    if (-not (Test-DwPrereqs)) { return }

    if ([string]::IsNullOrEmpty($ws)) {
        Write-Error "usage: dw <workspace-name>"
        return
    }

    $container = if ($env:DW_CONTAINER) { $env:DW_CONTAINER } else { "tools" }

    # Confirm we're logged in before anything else.
    $whoami = oc whoami 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Error "dw: not logged in to a cluster (oc whoami failed): $whoami"
        return
    }
    Write-DwDebug "logged in as $whoami"

    $ns = Resolve-DwNamespace
    if ([string]::IsNullOrEmpty($ns)) {
        Write-Error "dw: could not determine a namespace. Set `$env:DW_NAMESPACE explicitly."
        return
    }
    Write-DwDebug "namespace: $ns"

    # Grab the first Running pod for this workspace.
    Write-DwDebug "querying running pods for workspace '$ws'"
    $pod = oc get pods -n $ns `
        -l "controller.devfile.io/devworkspace_name=$ws" `
        --field-selector=status.phase=Running `
        -o "jsonpath={.items[0].metadata.name}" 2>&1
    $podExit = $LASTEXITCODE

    if ($podExit -ne 0) {
        Write-Error "dw: oc get pods failed (exit $podExit): $pod"
        return
    }

    if ([string]::IsNullOrEmpty($pod)) {
        Write-Error "dw: no running pod found for workspace '$ws' in namespace '$ns'"

        # Help the user: list what workspaces *do* exist and their phase.
        $names = oc get pods -n $ns `
            -l controller.devfile.io/devworkspace_name `
            -o "jsonpath={range .items[*]}{.metadata.labels.controller\.devfile\.io/devworkspace_name}{'\t'}{.status.phase}{'\n'}{end}" 2>&1 |
            Sort-Object -Unique
        if (-not [string]::IsNullOrEmpty($names)) {
            Write-Host "dw: workspaces in '$ns' (name / phase):" -ForegroundColor Yellow
            Write-Host ($names -join "`n")
        } else {
            Write-Host "dw: no DevWorkspace pods found in namespace '$ns'." -ForegroundColor Yellow
        }
        return
    }

    Write-Host "dw: exec into $pod (-c $container)" -ForegroundColor DarkGray
    oc exec -ti -n $ns $pod -c $container -- /bin/bash
}

# --- tab-completion for `dw` ---------------------------------------------
# `dw <TAB>` lists DevWorkspace names with a running pod in the resolved ns.
Register-ArgumentCompleter -CommandName dw -ParameterName ws -ScriptBlock {
    param($commandName, $parameterName, $wordToComplete, $commandAst, $fakeBoundParameters)

    $ns = Resolve-DwNamespace
    if ([string]::IsNullOrEmpty($ns)) { return }

    $names = oc get pods -n $ns `
        -l controller.devfile.io/devworkspace_name `
        -o "jsonpath={range .items[?(@.status.phase==`"Running`")]}{.metadata.labels.controller\.devfile\.io/devworkspace_name}{`"`n`"}{end}" `
        2>$null

    $names -split "`n" |
        Where-Object { $_ -and $_ -like "$wordToComplete*" } |
        Sort-Object -Unique |
        ForEach-Object { [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_) }
}

Export-ModuleMember -Function dw
