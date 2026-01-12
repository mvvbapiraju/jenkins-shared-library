import com.company.utils.CommonUtils

/**
 * runTests(config: ...)
 *
 * config:
 *  - stashName: 'workspace'
 *  - workDir: '.'
 *  - type: maven|gradle|node
 *  - testCmd: override
 *  - junitPatterns: '**/surefire-reports/*.xml' etc
 */
def call(Map config = [:]) {
    def u = new CommonUtils(this)

    def cfg = u.deepMerge([
            stashName: "workspace",
            workDir: ".",
            type: "maven",
            testCmd: "",
            junitPatterns: "**/target/surefire-reports/*.xml, **/build/test-results/test/*.xml"
    ], config ?: [:])

    u.requireKeys(cfg, ["stashName", "workDir", "type"])

    deleteDir()
    unstash cfg.stashName

    dir(cfg.workDir) {
        def cmd = cfg.testCmd?.trim()
        if (!cmd) {
            cmd = (cfg.type == "maven") ? "mvn -B -ntp test" :
                    (cfg.type == "gradle") ? "./gradlew test" :
                            (cfg.type == "node") ? "npm test" :
                                    null
        }
        if (!cmd) error "Unsupported test type: ${cfg.type}"

        try {
            u.shAssert(cmd, "Tests failed")
        } finally {
            // Always publish JUnit if available
            junit testResults: cfg.junitPatterns, allowEmptyResults: true
        }
    }
}
