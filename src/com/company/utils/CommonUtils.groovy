// What this utility covers:
// - Groovy classes & methods
// - Static vs instance methods
// - Maps, Lists, Closures
// - String interpolation
// - File operations
// - JSON parsing
// - Retry logic
// - Environment handling
// - Jenkins pipeline compatibility
// - Defensive programming

// This utility class lives under src/ in our shared library and is injected with the Jenkins steps context so it can safely call pipeline steps like sh, echo,
// and readFile. We kept business logic here and pipeline orchestration in vars/ to keep Jenkinsfiles thin.
// - passed steps explicitly to keep the class serializable
// - avoided static pipeline calls inside utility classes
// - Retry logic with backoff reduced flaky pipeline failures
// - Guard methods prevented invalid deployments early
// - Utilities were versioned alongside the shared library

// How this utility is used in a Jenkinsfile:
// def utils = new com.company.utils.CommonUtils(this)
//
// utils.logInfo("Starting pipeline")
// utils.requireOneOf(params.ENV, ['dev', 'qa', 'prod'], 'ENV')
//
// def version = utils.generateVersion(env.BUILD_NUMBER)

/**
 * Common utilities for Jenkins shared libraries.
 * - Must be Serializable (pipeline CPS)
 * - Keep state minimal
 * - Wrap all Jenkins steps with steps.<step>
 */

package com.company.utils

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.text.SimpleDateFormat

class CommonUtils implements Serializable {

    private final def steps

    /**
     * Constructor required for Jenkins shared libraries
     */
    CommonUtils(steps) {
        this.steps = steps
    }

    // ---------- Validation / Config ----------

    void requireKeys(Map cfg, List<String> requiredKeys) {
        def missing = requiredKeys.findAll { !cfg.containsKey(it) || cfg[it] == null || "${cfg[it]}".trim() == "" }
        if (missing) {
            steps.error("Missing required config key(s): ${missing}. Provided keys: ${cfg.keySet()}")
        }
    }

    Map deepMerge(Map base, Map override) {
        Map out = [:]
        out.putAll(base ?: [:])
        (override ?: [:]).each { k, v ->
            if (out[k] instanceof Map && v instanceof Map) {
                out[k] = deepMerge((Map) out[k], (Map) v)
            } else {
                out[k] = v
            }
        }
        return out
    }

    // ---------- Shell helpers ----------

    String shStdout(String cmd, boolean quiet = false) {
        if (!quiet) steps.echo("[sh] ${cmd}")
        return steps.sh(script: cmd, returnStdout: true).trim()
    }

    int shStatus(String cmd, boolean quiet = false) {
        if (!quiet) steps.echo("[sh] ${cmd}")
        return steps.sh(script: cmd, returnStatus: true) as int
    }

    void shAssert(String cmd, String errMsg = "Command failed") {
        int rc = shStatus(cmd)
        if (rc != 0) {
            steps.error("${errMsg}. Exit code=${rc}. Cmd=${cmd}")
        }
    }

    // ---------- Retry / wait ----------

    def retryWithBackoff(int maxAttempts = 3, int initialSeconds = 3, double factor = 2.0, Closure body) {
        int attempt = 1
        int sleepSec = initialSeconds
        while (true) {
            try {
                return body.call(attempt)
            } catch (Throwable t) {
                if (attempt >= maxAttempts) throw t
                steps.echo("Attempt ${attempt}/${maxAttempts} failed: ${t.message}")
                steps.echo("Sleeping ${sleepSec}s before retry...")
                steps.sleep(time: sleepSec, unit: 'SECONDS')
                sleepSec = Math.max(1, (int) Math.round(sleepSec * factor))
                attempt++
            }
        }
    }

    def waitUntilOrFail(int timeoutMinutes = 10, int pollSeconds = 10, String what = "condition", Closure<Boolean> condition) {
        steps.timeout(time: timeoutMinutes, unit: "MINUTES") {
            steps.waitUntil(initialRecurrencePeriod: pollSeconds * 1000) {
                boolean ok = condition.call()
                if (!ok) {
                    steps.echo("Waiting for ${what} ...")
                }
                return ok
            }
        }
    }

    // ---------- JSON helpers ----------

    Map readJsonFile(String path) {
        def txt = steps.readFile(file: path)
        return (Map) (new JsonSlurperClassic().parseText(txt))
    }

    void writeJsonFile(String path, Object obj, boolean pretty = true) {
        def json = pretty ? JsonOutput.prettyPrint(JsonOutput.toJson(obj)) : JsonOutput.toJson(obj)
        steps.writeFile(file: path, text: json)
    }

    // ---------- Credential-safe echo ----------

    String redact(String text, List<String> secrets) {
        if (!text) return text
        def out = text
        (secrets ?: []).each { s ->
            if (s) out = out.replace(s, "****")
        }
        return out
    }

    // ---------- AWS helpers (CLI-based) ----------

    /**
     * Preferred pattern in enterprises:
     * - Agent already has identity (EC2 instance profile or IRSA)
     * - For cross-account: use STS AssumeRole to get short-lived creds
     *
     * cfg:
     *  - roleArn (optional)
     *  - sessionName (optional)
     *  - region (required for aws cli calls)
     */
    def withAwsAssumeRole(Map cfg = [:], Closure body) {
        requireKeys(cfg, ["region"])

        def roleArn = cfg.roleArn
        def sessionName = cfg.sessionName ?: "jenkins-${steps.env.JOB_NAME}-${steps.env.BUILD_NUMBER}".replaceAll("[^A-Za-z0-9+=,.@-]", "-")
        def region = cfg.region

        if (!roleArn) {
            // No assume-role required; rely on ambient creds (instance profile / IRSA)
            steps.withEnv(["AWS_REGION=${region}", "AWS_DEFAULT_REGION=${region}"]) {
                return body.call()
            }
        }

        steps.echo("Assuming AWS role: ${roleArn} (session=${sessionName}) in region=${region}")

        def json = shStdout("""
      aws sts assume-role \
        --role-arn '${roleArn}' \
        --role-session-name '${sessionName}' \
        --duration-seconds 3600 \
        --output json
    """.stripIndent())

        def parsed = (Map) (new JsonSlurperClassic().parseText(json))
        def creds = parsed.Credentials

        steps.withEnv([
                "AWS_ACCESS_KEY_ID=${creds.AccessKeyId}",
                "AWS_SECRET_ACCESS_KEY=${creds.SecretAccessKey}",
                "AWS_SESSION_TOKEN=${creds.SessionToken}",
                "AWS_REGION=${region}",
                "AWS_DEFAULT_REGION=${region}",
        ]) {
            return body.call()
        }
    }

    /**
     * Docker login to ECR (works for private ECR).
     * registry example: 123456789012.dkr.ecr.us-east-1.amazonaws.com
     */
    void ecrLogin(String region, String registry) {
        requireKeys([region: region, registry: registry], ["region", "registry"])
        shAssert("""
      aws ecr get-login-password --region '${region}' | docker login --username AWS --password-stdin '${registry}'
    """.stripIndent(), "ECR login failed")
    }






    /* -----------------------------------
       VERSIONING & BUILD HELPERS
    ----------------------------------- */

    static String generateVersion(String buildNumber) {
        def date = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date())
        return "1.0.${buildNumber}-${date}"
    }

    static String sanitizeBranchName(String branch) {
        return branch.replaceAll('[^a-zA-Z0-9.-]', '-').toLowerCase()
    }

    /* -----------------------------------
       ENVIRONMENT & PARAMETER HELPERS
    ----------------------------------- */

    String getEnv(String key, String defaultValue = '') {
        return steps.env[key] ?: defaultValue
    }

    boolean isProd(String environment) {
        return environment?.toLowerCase() == 'prod'
    }

    /* -----------------------------------
       LOGGING & OUTPUT
    ----------------------------------- */

    void logInfo(String message) {
        steps.echo "[INFO] ${message}"
    }

    void logWarn(String message) {
        steps.echo "[WARN] ${message}"
    }

    void logError(String message) {
        steps.echo "[ERROR] ${message}"
    }

    /* -----------------------------------
       RETRY & RESILIENCE
    ----------------------------------- */

    void retryWithBackoff(int retries = 3, int sleepSeconds = 10, Closure action) {
        int attempt = 0
        while (attempt < retries) {
            try {
                action.call()
                return
            } catch (Exception e) {
                attempt++
                if (attempt >= retries) {
                    throw e
                }
                logWarn("Attempt ${attempt} failed. Retrying in ${sleepSeconds}s...")
                steps.sleep(time: sleepSeconds, unit: 'SECONDS')
            }
        }
    }

    /* -----------------------------------
       FILE & JSON UTILITIES
    ----------------------------------- */

    Map readJsonFile(String filePath) {
        def content = steps.readFile(filePath)
        return new JsonSlurper().parseText(content) as Map
    }

    void writeJsonFile(String filePath, Map data) {
        def json = JsonOutput.prettyPrint(JsonOutput.toJson(data))
        steps.writeFile(file: filePath, text: json)
    }

    boolean fileExists(String filePath) {
        return steps.fileExists(filePath)
    }

    /* -----------------------------------
       SHELL & COMMAND HELPERS
    ----------------------------------- */

    String runCommand(String command, boolean returnStdout = true) {
        return steps.sh(
                script: command,
                returnStdout: returnStdout
        ).trim()
    }

    /* -----------------------------------
       VALIDATION & GUARDS
    ----------------------------------- */

    void requireNonEmpty(String value, String fieldName) {
        if (!value?.trim()) {
            throw new IllegalArgumentException("${fieldName} must not be empty")
        }
    }

    void requireOneOf(String value, List allowedValues, String fieldName) {
        if (!allowedValues.contains(value)) {
            throw new IllegalArgumentException(
                    "${fieldName} must be one of ${allowedValues.join(', ')}"
            )
        }
    }

    /* -----------------------------------
       MAP & LIST HELPERS
    ----------------------------------- */

    Map mergeMaps(Map base, Map override) {
        return base + override
    }

    List uniqueList(List items) {
        return items.unique()
    }

    /* -----------------------------------
       PIPELINE CONTEXT HELPERS
    ----------------------------------- */

    boolean isPullRequest() {
        return steps.env.CHANGE_ID != null
    }

    boolean isMainBranch() {
        return steps.env.BRANCH_NAME == 'main'
    }

}
