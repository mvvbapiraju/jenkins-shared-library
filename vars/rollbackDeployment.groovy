import com.company.utils.CommonUtils

// Stop an in-progress CodeDeploy deployment
// Auto-rollback (when enabled / supported)
// Print last N deployment lifecycle events (what Fidelity interviewers LOVE: “how do you debug it?”)
// Show the final status + error message clearly
// Optionally perform a manual ECS rollback (fallback pattern) by updating the service to a previous task definition (if you provide it)

/**
 * rollbackDeployment(config: ...)
 *
 * Primary: CodeDeploy stop + auto rollback (ECS Blue/Green)
 * Secondary fallback: manual ECS rollback (update service to previous task def)
 *
 * config:
 *  - aws:
 *      - region (required)
 *      - roleArn (optional)
 *  - deploymentId (required) : CodeDeploy deployment id
 *  - mode: stopOnly | stopAndAutoRollback | autoRollbackOnly | manualEcsRollback
 *
 *  - events:
 *      - printEvents: true/false (default true)
 *      - max: number of events to show (default 20)
 *
 *  - manualEcs (optional):
 *      - cluster: ECS cluster name
 *      - service: ECS service name
 *      - taskDefinition: task definition ARN (previous known good)
 *
 * Notes:
 *  - For ECS Blue/Green, CodeDeploy auto-rollback is typically triggered by alarms/health checks.
 *  - This step helps you stabilize quickly and produces debug output that saves time.
 */
def call(Map config = [:]) {
    def u = new CommonUtils(this)

    def cfg = u.deepMerge([
            aws: [ region: "", roleArn: "" ],
            deploymentId: "",
            mode: "stopOnly",
            events: [ printEvents: true, max: 20 ],
            manualEcs: [ cluster: "", service: "", taskDefinition: "" ]
    ], config ?: [:])

    u.requireKeys(cfg.aws as Map, ["region"])
    u.requireKeys(cfg, ["deploymentId", "mode"])

    def region = cfg.aws.region.toString()
    def roleArn = (cfg.aws.roleArn ?: "").toString()
    def deploymentId = cfg.deploymentId.toString()

    u.withAwsAssumeRole([region: region, roleArn: roleArn], {

        echo "=== Rollback/Stop handler ==="
        echo "deploymentId=${deploymentId}"
        echo "mode=${cfg.mode}"
        echo "region=${region}"

        // Helper: print status + error
        def printDeploymentSummary = {
            def status = u.shStdout(
                    "aws deploy get-deployment --deployment-id '${deploymentId}' --query 'deploymentInfo.status' --output text",
                    true
            )
            def creator = u.shStdout(
                    "aws deploy get-deployment --deployment-id '${deploymentId}' --query 'deploymentInfo.creator' --output text 2>/dev/null || true",
                    true
            )
            def err = u.shStdout(
                    "aws deploy get-deployment --deployment-id '${deploymentId}' --query 'deploymentInfo.errorInformation.message' --output text 2>/dev/null || true",
                    true
            )
            echo "CodeDeploy status=${status} creator=${creator}"
            if (err && err != "None" && err != "null") {
                echo "CodeDeploy error=${err}"
            }
            return status
        }

        // Helper: show recent deployment events (super helpful during debugging)
        def printDeploymentEvents = {
            if (!(cfg.events?.printEvents)) return
            int maxEvents = (cfg.events?.max ?: 20) as int

            echo "=== Last ${maxEvents} CodeDeploy lifecycle events (most recent first) ==="
            // We rely on output being stable; if jq isn't available, use AWS query formatting.
            // Sort by startTime desc and print top N.
            sh """
        aws deploy list-deployment-instances --deployment-id '${deploymentId}' --output json > .cd_instances.json
        python3 - <<'PY'
import json, subprocess
data=json.load(open('.cd_instances.json'))
ids=data.get('instancesList', [])
print("Instances:", ids if ids else "None")
PY
      """.stripIndent()

            // Attempt to print lifecycle events for first instance, if any.
            // (ECS blue/green often has one “instance” id representing the target.)
            sh """
        python3 - <<'PY'
import json, subprocess, sys
data=json.load(open('.cd_instances.json'))
ids=data.get('instancesList', [])
if not ids:
  print("No deployment instances returned (may be expected depending on deployment type).")
  sys.exit(0)
inst=ids[0]
print("Using instance id:", inst)
PY
      """.stripIndent()

            // Print lifecycle events for the first instance id
            // If this fails (some deployments do), we still continue.
            sh """
        set +e
        inst=$(python3 - <<'PY'
import json
data=json.load(open('.cd_instances.json'))
ids=data.get('instancesList', [])
print(ids[0] if ids else "")
PY
        )
        if [ -n "$inst" ]; then
          aws deploy get-deployment-instance \
            --deployment-id '${deploymentId}' \
            --instance-id "$inst" \
            --query "instanceSummary.lifecycleEvents" \
            --output json > .cd_events.json

          python3 - <<'PY'
import json, datetime
events=json.load(open('.cd_events.json')) if open('.cd_events.json').read().strip() else []
# Each item: {lifecycleEventName,status,startTime,endTime,diagnostics...}
def ts(x):
  if not x: return ""
  # awscli returns like "2026-01-12T12:34:56.000000+00:00"
  return x
events_sorted = sorted(events, key=lambda e: e.get('startTime') or "", reverse=True)
maxn = ${maxEvents}
for e in events_sorted[:maxn]:
  name=e.get('lifecycleEventName')
  st=e.get('status')
  start=ts(e.get('startTime'))
  end=ts(e.get('endTime'))
  diag=e.get('diagnostics') or {}
  msg=diag.get('message') or ""
  err=diag.get('errorCode') or ""
  print(f"- {name}: {st}  start={start}  end={end}")
  if msg or err:
    print(f"  diagnostics: errorCode={err} message={msg}")
PY
        fi
        set -e
      """.stripIndent()
        }

        // 1) Snapshot info before action
        echo "=== Deployment Summary (before) ==="
        def beforeStatus = printDeploymentSummary.call()
        printDeploymentEvents.call()

        // 2) Apply mode actions
        switch (cfg.mode) {

            case "stopOnly":
                echo "Action: stop deployment (no manual ECS rollback)."
                sh "aws deploy stop-deployment --deployment-id '${deploymentId}' || true"
                break

            case "stopAndAutoRollback":
                echo "Action: stop deployment with auto-rollback enabled (if supported)."
                sh "aws deploy stop-deployment --deployment-id '${deploymentId}' --auto-rollback-enabled || true"
                break

            case "autoRollbackOnly":
                echo "Action: attempt to stop deployment with auto rollback enabled (no explicit stop-only path)."
                sh "aws deploy stop-deployment --deployment-id '${deploymentId}' --auto-rollback-enabled || true"
                break

            case "manualEcsRollback":
                echo "Action: manual ECS rollback (and also stop CodeDeploy)."
                sh "aws deploy stop-deployment --deployment-id '${deploymentId}' --auto-rollback-enabled || true"

                def m = cfg.manualEcs as Map
                u.requireKeys(m, ["cluster", "service", "taskDefinition"])

                echo "Updating ECS service to previous taskDefinition=${m.taskDefinition}"
                u.shAssert("""
          aws ecs update-service \
            --cluster '${m.cluster}' \
            --service '${m.service}' \
            --task-definition '${m.taskDefinition}' \
            --force-new-deployment \
            --output json > ecs_update.json
        """.stripIndent(), "Manual ECS rollback failed")

                echo "Waiting for ECS service to stabilize..."
                u.shAssert("""
          aws ecs wait services-stable --cluster '${m.cluster}' --services '${m.service}'
        """.stripIndent(), "ECS did not stabilize after rollback")

                echo "✅ Manual ECS rollback completed and service stabilized."
                break

            default:
                error "Unsupported rollback mode: ${cfg.mode}. Valid: stopOnly|stopAndAutoRollback|autoRollbackOnly|manualEcsRollback"
        }

        // 3) Summary after action
        echo "=== Deployment Summary (after action) ==="
        def afterStatus = printDeploymentSummary.call()

        // Often CodeDeploy transitions after stop; we can wait a short period for status to settle.
        // (Not mandatory, but makes output clearer for interviews.)
        if (!(afterStatus in ["Succeeded", "Failed", "Stopped"])) {
            echo "Waiting briefly for CodeDeploy status to settle..."
            u.waitUntilOrFail(5, 10, "CodeDeploy to reach terminal state", {
                def st = u.shStdout(
                        "aws deploy get-deployment --deployment-id '${deploymentId}' --query 'deploymentInfo.status' --output text",
                        true
                )
                echo "CodeDeploy status=${st}"
                return (st in ["Succeeded", "Failed", "Stopped"])
            })
        }

        echo "=== Final Deployment Summary ==="
        def finalStatus = printDeploymentSummary.call()
        printDeploymentEvents.call()

        if (finalStatus == "Succeeded") {
            echo "Deployment succeeded — no rollback required."
        } else {
            echo "Rollback/stop path executed. Final CodeDeploy status=${finalStatus}"
        }
    })
}
