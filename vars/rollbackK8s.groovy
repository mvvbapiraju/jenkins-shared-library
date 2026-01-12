import com.company.utils.CommonUtils
import groovy.json.JsonSlurperClassic

// Supports:
// - helm rollback
// - kubectl rollout undo
// - printing the last N events + pod logs from failed pods
// - optional canary rollback logic

/**
 * rollbackK8s(config: ...)
 *
 * Rollback helper for EKS / Kubernetes deployments.
 * Modes:
 *  - helmRollback
 *  - kubectlUndo
 *
 * Optional: bootstrap kube context for EKS using aws eks update-kubeconfig.
 *
 * config:
 *  - mode: "helmRollback" | "kubectlUndo" (required)
 *
 *  - eks (optional):
 *      - enabled: true/false (default false)
 *      - region (required if enabled)
 *      - clusterName (required if enabled)
 *      - roleArn (optional assumeRole)
 *      - kubeconfigPath (optional; default $WORKSPACE/.kubeconfig)
 *
 *  - namespace: (default "default")
 *
 *  - helm:
 *      - release (required for helmRollback)
 *      - revision (optional; if missing, auto-picks previous successful revision)
 *      - timeoutMinutes (default 10)
 *
 *  - kubectl:
 *      - kind (default "deployment")
 *      - name (required for kubectlUndo)
 *      - toRevision (optional; maps to --to-revision)
 *      - timeoutMinutes (default 10)
 *
 *  - diagnostics:
 *      - enabled (default true)
 *      - labelSelector (optional, used to filter pods e.g. "app=my-service")
 *      - container (optional for logs)
 *      - maxPods (default 5)
 *      - maxEvents (default 30)
 *      - logLines (default 200)
 *      - includePreviousLogs (default true)  (kubectl logs --previous)
 */
def call(Map config = [:]) {
    def u = new CommonUtils(this)

    def cfg = u.deepMerge([
            mode: "",
            namespace: "default",

            eks: [
                    enabled: false,
                    region: "",
                    clusterName: "",
                    roleArn: "",
                    kubeconfigPath: "${env.WORKSPACE}/.kubeconfig"
            ],

            helm: [
                    release: "",
                    revision: "",           // auto-detect if empty
                    timeoutMinutes: 10
            ],

            kubectl: [
                    kind: "deployment",
                    name: "",
                    toRevision: "",
                    timeoutMinutes: 10
            ],

            diagnostics: [
                    enabled: true,
                    labelSelector: "",
                    container: "",
                    maxPods: 5,
                    maxEvents: 30,
                    logLines: 200,
                    includePreviousLogs: true
            ]
    ], config ?: [:])

    u.requireKeys(cfg, ["mode"])
    u.requireKeys(cfg, ["namespace"])

    // ---------------------------
    // 0) Optional: EKS kubecontext bootstrap
    // ---------------------------
    if (cfg.eks.enabled) {
        u.requireKeys(cfg.eks as Map, ["region", "clusterName", "kubeconfigPath"])
        def region = cfg.eks.region.toString()
        def cluster = cfg.eks.clusterName.toString()
        def roleArn = (cfg.eks.roleArn ?: "").toString()
        def kubeconfig = cfg.eks.kubeconfigPath.toString()

        echo "EKS bootstrap enabled → updating kubeconfig for cluster=${cluster}, region=${region}"
        u.withAwsAssumeRole([region: region, roleArn: roleArn], {
            u.shAssert("""
        mkdir -p '${env.WORKSPACE}'
        aws eks update-kubeconfig \
          --name '${cluster}' \
          --region '${region}' \
          --kubeconfig '${kubeconfig}'
      """.stripIndent(), "Failed to update kubeconfig for EKS")
        })

        // Make kubectl/helm pick it up
        withEnv(["KUBECONFIG=${kubeconfig}"]) {
            _runRollbackAndDiagnostics(u, cfg)
        }
    } else {
        _runRollbackAndDiagnostics(u, cfg)
    }
}

/**
 * Internal runner:
 * - Pre diagnostics snapshot
 * - Rollback
 * - Post diagnostics snapshot + wait for stabilization
 */
def _runRollbackAndDiagnostics(CommonUtils u, Map cfg) {
    def ns = cfg.namespace.toString()
    def mode = cfg.mode.toString()

    echo "rollbackK8s → mode=${mode}, namespace=${ns}"

    if (cfg.diagnostics.enabled) {
        _diagnostics(u, cfg, "BEFORE_ROLLBACK")
    }

    switch (mode) {
        case "helmRollback":
            _helmRollback(u, cfg)
            break

        case "kubectlUndo":
            _kubectlUndo(u, cfg)
            break

        default:
            error "Unsupported mode='${mode}'. Valid: helmRollback|kubectlUndo"
    }

    if (cfg.diagnostics.enabled) {
        _diagnostics(u, cfg, "AFTER_ROLLBACK")
    }
}

// ---------------------------
// Helm rollback
// ---------------------------
def _helmRollback(CommonUtils u, Map cfg) {
    def ns = cfg.namespace.toString()
    def rel = cfg.helm.release?.toString()
    u.requireKeys(cfg.helm as Map, ["release"])

    // Auto pick revision if not provided:
    // Choose "previous successful" (last-1 where status=deployed/superseded).
    def revision = (cfg.helm.revision ?: "").toString().trim()
    if (!revision) {
        echo "Helm revision not provided → auto-detecting previous revision..."
        revision = _detectPreviousHelmRevision(u, rel, ns)
        echo "Auto-selected helm rollback revision=${revision}"
    }

    int timeout = (cfg.helm.timeoutMinutes ?: 10) as int
    echo "Executing: helm rollback ${rel} ${revision} -n ${ns} --wait --timeout ${timeout}m"
    u.shAssert("""
    helm rollback '${rel}' '${revision}' -n '${ns}' --wait --timeout '${timeout}m'
  """.stripIndent(), "Helm rollback failed")

    // Optional: if helm supports rollout status via kubectl anyway, we can rely on --wait.
    echo "✅ Helm rollback completed (waited)."
}

def _detectPreviousHelmRevision(CommonUtils u, String release, String namespace) {
    // Use JSON output so we can reliably parse.
    // We select the most recent revision that is NOT the current deployed revision.
    // Priority:
    //  1) latest revision with status=deployed is current; choose next best = superseded/deployed just before it
    //  2) else fallback to max(revision-1)
    def json = u.shStdout("""
    helm history '${release}' -n '${namespace}' --max 20 -o json
  """.stripIndent(), true)

    def parsed = new JsonSlurperClassic().parseText(json)
    if (!(parsed instanceof List) || parsed.isEmpty()) {
        error "Unable to read helm history for release='${release}' namespace='${namespace}'"
    }

    // Find current deployed (highest revision with deployed)
    def deployed = parsed.findAll { it?.status?.toString()?.toLowerCase() == "deployed" }
    def currentDeployedRev = deployed ? (deployed.max { (it.revision as int) }.revision as int) : null

    // Candidate revisions exclude current deployed
    def candidates = parsed.findAll { it?.revision != null }
            .findAll { currentDeployedRev == null || ((it.revision as int) < currentDeployedRev) }

    // Prefer "superseded" (previous release) or "deployed" (rare) as rollback targets
    def preferred = candidates.findAll {
        def s = it.status?.toString()?.toLowerCase()
        return (s in ["superseded", "deployed"])
    }

    def target = preferred ? preferred.max { (it.revision as int) } :
            candidates ? candidates.max { (it.revision as int) } :
                    null

    if (!target) {
        error "Could not determine a previous helm revision to rollback to for release='${release}'"
    }

    return (target.revision as int).toString()
}

// ---------------------------
// kubectl rollout undo
// ---------------------------
def _kubectlUndo(CommonUtils u, Map cfg) {
    def ns = cfg.namespace.toString()
    def kind = (cfg.kubectl.kind ?: "deployment").toString()
    def name = cfg.kubectl.name?.toString()
    u.requireKeys(cfg.kubectl as Map, ["name"])

    def toRev = (cfg.kubectl.toRevision ?: "").toString().trim()
    def revArg = toRev ? "--to-revision='${toRev}'" : ""
    int timeout = (cfg.kubectl.timeoutMinutes ?: 10) as int

    echo "Executing: kubectl rollout undo ${kind}/${name} -n ${ns} ${revArg}"
    u.shAssert("""
    kubectl rollout undo ${kind}/${name} -n '${ns}' ${revArg}
  """.stripIndent(), "kubectl rollout undo failed")

    echo "Waiting for rollout to complete..."
    u.shAssert("""
    kubectl rollout status ${kind}/${name} -n '${ns}' --timeout='${timeout}m'
  """.stripIndent(), "Rollback rollout did not complete in time")

    echo "✅ kubectl rollback completed and rollout is stable."
}

// ---------------------------
// Diagnostics (events/pods/describe/logs)
// ---------------------------
def _diagnostics(CommonUtils u, Map cfg, String phase) {
    def ns = cfg.namespace.toString()
    def sel = (cfg.diagnostics.labelSelector ?: "").toString().trim()
    def selArg = sel ? "-l '${sel}'" : ""
    int maxEvents = (cfg.diagnostics.maxEvents ?: 30) as int
    int maxPods = (cfg.diagnostics.maxPods ?: 5) as int
    int logLines = (cfg.diagnostics.logLines ?: 200) as int
    def container = (cfg.diagnostics.container ?: "").toString().trim()
    def containerArg = container ? "-c '${container}'" : ""
    boolean includePrev = (cfg.diagnostics.includePreviousLogs != false)

    echo "================= K8S DIAGNOSTICS (${phase}) ================="
    echo "namespace=${ns} selector=${sel ?: '(none)'}"

    // 1) Basic state
    sh """
    set +e
    echo "---- kubectl get nodes (brief) ----"
    kubectl get nodes -o wide | head -n 20 || true

    echo "---- kubectl get pods ----"
    kubectl get pods -n '${ns}' ${selArg} -o wide || true

    echo "---- kubectl get deploy/rs/svc/ing (if present) ----"
    kubectl get deploy -n '${ns}' ${selArg} -o wide 2>/dev/null || true
    kubectl get rs -n '${ns}' ${selArg} -o wide 2>/dev/null || true
    kubectl get svc -n '${ns}' ${selArg} -o wide 2>/dev/null || true
    kubectl get ingress -n '${ns}' ${selArg} -o wide 2>/dev/null || true
    set -e
  """.stripIndent()

    // 2) Events (most recent)
    sh """
    set +e
    echo "---- kubectl events (most recent ${maxEvents}) ----"
    kubectl get events -n '${ns}' --sort-by=.metadata.creationTimestamp | tail -n ${maxEvents} || true
    set -e
  """.stripIndent()

    // 3) Pick top failing pods (CrashLoop/ImagePull/NotReady/Pending) and describe/log them
    // We do simple heuristics: find pods not in Running/Completed, or Running but not Ready.
    def podList = u.shStdout("""
    set +e
    kubectl get pods -n '${ns}' ${selArg} -o json
    set -e
  """.stripIndent(), true)

    def parsed = new JsonSlurperClassic().parseText(podList) as Map
    def items = (parsed.items ?: []) as List

    def badPods = []
    items.each { p ->
        def name = p?.metadata?.name?.toString()
        def phase0 = p?.status?.phase?.toString()
        def conditions = (p?.status?.conditions ?: []) as List
        def readyCond = conditions.find { it?.type?.toString() == "Ready" }
        def isReady = readyCond?.status?.toString() == "True"

        // "Bad" if not Running, or Running but not Ready
        if (name && ((phase0 != "Running" && phase0 != "Succeeded") || (phase0 == "Running" && !isReady))) {
            badPods << name
        }
    }

    badPods = badPods.take(maxPods)
    if (badPods.isEmpty()) {
        echo "No obvious failing pods detected (based on phase/ready). Skipping describe/log dump."
        echo "============================================================="
        return
    }

    echo "Failing pods (top ${maxPods}): ${badPods}"

    badPods.each { pod ->
        sh """
      set +e
      echo "---- describe pod/${pod} ----"
      kubectl describe pod '${pod}' -n '${ns}' || true

      echo "---- logs (tail ${logLines}) pod/${pod} ${container ? "container=${container}" : ""} ----"
      kubectl logs '${pod}' -n '${ns}' ${containerArg} --tail=${logLines} || true
    """.stripIndent()

        if (includePrev) {
            sh """
        set +e
        echo "---- logs --previous (tail ${logLines}) pod/${pod} ${container ? "container=${container}" : ""} ----"
        kubectl logs '${pod}' -n '${ns}' ${containerArg} --previous --tail=${logLines} || true
      """.stripIndent()
        }
    }

    echo "============================================================="
}
