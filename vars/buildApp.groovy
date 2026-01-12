package com.company.utils

/**
 * Versioning utilities (SemVer-ish) for tags/images/artifacts.
 *
 * IMPORTANT:
 * - Prefer @NonCPS only for pure computations.
 * - Don't call Jenkins steps inside @NonCPS methods.
 */
class VersionUtils implements Serializable {

    private final def steps
    private final CommonUtils u

    VersionUtils(def steps) {
        this.steps = steps
        this.u = new CommonUtils(steps)
    }

    /**
     * Compute an "artifact version" for CI/CD.
     * Strategies:
     *  - gitTag: prefer exact tag if build is on a tag
     *  - gitDescribe: tag + commits + sha
     *  - sha: commit sha only
     */
    String computeVersion(Map cfg = [:]) {
        def strategy = cfg.strategy ?: "gitDescribe" // gitTag|gitDescribe|sha
        def shortSha = u.shStdout("git rev-parse --short=12 HEAD", true)
        def branch = safeBranch()

        switch (strategy) {
            case "gitTag":
                def tag = u.shStdout("git describe --tags --exact-match 2>/dev/null || true", true)
                return tag?.trim() ? tag.trim() : "${branch}-${shortSha}"
            case "sha":
                return "${branch}-${shortSha}"
            case "gitDescribe":
            default:
                def desc = u.shStdout("git describe --tags --always --dirty 2>/dev/null || git rev-parse --short=12 HEAD", true)
                // make it docker-tag safe
                return sanitizeForTag(desc)
        }
    }

    /**
     * Docker image tags can’t contain certain chars.
     */
    @NonCPS
    String sanitizeForTag(String s) {
        if (!s) return "unknown"
        return s
                .replaceAll("[^A-Za-z0-9_.-]", "-")
                .replaceAll("-{2,}", "-")
                .take(127)
    }

    String safeBranch() {
        // Jenkins may set BRANCH_NAME. If not, use git.
        def b = steps.env.BRANCH_NAME
        if (b) return sanitizeForTag(b)
        def gitBranch = u.shStdout("git rev-parse --abbrev-ref HEAD", true)
        return sanitizeForTag(gitBranch)
    }
}
