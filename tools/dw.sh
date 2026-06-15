# --- DevWorkspace shell helper -------------------------------------------
# Drop this into your ~/.zshrc (or `source` this file from it).
#
# Usage:  dw <workspace-name>
#   Opens an interactive bash shell in the `tools` container of the
#   DevWorkspace named <workspace-name>.
#
# Requires: oc, jq (and a logged-in cluster context).
# Configure the namespace once in your ~/.zshrc, e.g.:
#   export DW_NAMESPACE="devspaces-bruce-ziax-com-wfk9kj"
# -------------------------------------------------------------------------
dw() {
  local ws="$1"
  local ns="${DW_NAMESPACE:-}"
  local container="${DW_CONTAINER:-tools}"

  if [[ -z "$ws" ]]; then
    echo "usage: dw <workspace-name>" >&2
    return 2
  fi
  if [[ -z "$ns" ]]; then
    echo "dw: DW_NAMESPACE is not set (export it in your ~/.zshrc)" >&2
    return 2
  fi

  # DevWorkspace pods are labeled with the workspace name. Grab the first
  # Running pod for this workspace.
  local pod
  pod="$(oc get pods -n "$ns" \
    -l "controller.devfile.io/devworkspace_name=$ws" \
    --field-selector=status.phase=Running \
    -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)"

  if [[ -z "$pod" ]]; then
    echo "dw: no running pod found for workspace '$ws' in namespace '$ns'" >&2
    return 1
  fi

  echo "dw: exec into $pod (-c $container)" >&2
  oc exec -ti -n "$ns" "$pod" -c "$container" -- /bin/bash
}

# --- zsh tab-completion for `dw` -----------------------------------------
# `dw <TAB>` lists the DevWorkspace names that currently have a running pod
# in $DW_NAMESPACE.
_dw() {
  local ns="${DW_NAMESPACE:-}"
  [[ -z "$ns" ]] && return 1

  local -a workspaces
  workspaces=(${(f)"$(oc get pods -n "$ns" \
    -l controller.devfile.io/devworkspace_name \
    -o jsonpath='{range .items[?(@.status.phase=="Running")]}{.metadata.labels.controller\.devfile\.io/devworkspace_name}{"\n"}{end}' \
    2>/dev/null | sort -u)"})

  compadd -a workspaces
}
compdef _dw dw

