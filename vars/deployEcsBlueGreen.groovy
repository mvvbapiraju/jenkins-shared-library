import com.company.utils.CommonUtils
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

/**
 * deployEcsBlueGreen(config: ...)
 *
 * Adds support for libraryResource templates:
 *  - resources/sample/appspec.yaml
 *  - resources/sample/taskdef.json
 *
 * Usage:
 *  deployEcsBlueGreen(config: [
 *    useSampleResources: true,
 *    aws: [ region: "us-east-1", roleArn: "arn:aws:iam::123:role/jenkins-codedeploy" ],
 *    codedeploy: [ applicationName: "my-ecs-app", deploymentGroup: "my-ecs-dg" ],
 *    image: env.PUBLISHED_IMAGE,
 *    containerName: "app",
 *    revision: [ type: "s3", bucket: "my-codedeploy-bucket", keyPrefix: "revisions/my-service" ]
 *  ])
 *
 * config:
 *  - useSampleResources: boolean (default false)
 *  - sampleResources:
 *      - appspec: "sample/appspec.yaml"  (path under resources/)
 *      - taskdef: "sample/taskdef.json"
 *  - artifacts:
 *      - appspecPath: "appspec.yaml" (workspace output path)
 *      - taskDefPath: "taskdef.json"
 *  - templateReplacements: Map<String,String> (optional; simple string replace on templates)
 *  - containerName: which container in taskdef to update (default "app")
 *  - image: full image URI (tag or digest). If set, we inject into taskdef.json.
 *
 *  - aws:
 *      - region (required)
 *      - roleArn (optional assumeRole)
 *  - codedeploy:
 *      - applicationName (required)
 *      - deploymentGroup (required)
 *
 *  - revision:
 *      - type: "s3" | "inline" (default "s3" if bucket provided else "inline")
 *      - bucket: S3 bucket name (for "s3")
 *      - keyPrefix: key prefix (for "s3") e.g. "revisions/my-service"
 */
def call(Map config = [:]) {
    def u = new CommonUtils(this)

    def cfg = u.deepMerge([
            useSampleResources: false,
            sampleResources: [
                    appspec: "sample/appspec.yaml",
                    taskdef: "sample/taskdef.json"
            ],
            artifacts: [
                    appspecPath: "appspec.yaml",
                    taskDefPath: "taskdef.json"
            ],
            templateReplacements: [:],
            containerName: "app",
            image: "",

            aws: [ region: "", roleArn: "" ],
            codedeploy: [ applicationName: "", deploymentGroup: "" ],

            revision: [
                    type: "",           // "s3" or "inline" (auto decided)
                    bucket: "",         // for s3
                    keyPrefix: ""       // for s3
            ]
    ], config ?: [:])

    u.requireKeys(cfg.aws as Map, ["region"])
    u.requireKeys(cfg.codedeploy as Map, ["applicationName", "deploymentGroup"])
    u.requireKeys(cfg.artifacts as Map, ["appspecPath", "taskDefPath"])

    // ------------------------------------------------------------
    // 1) Materialize templates (either from workspace or from resources/)
    // ------------------------------------------------------------
    if (cfg.useSampleResources) {
        echo "useSampleResources=true → loading templates from shared library resources/"

        def appspecTemplate = libraryResource(cfg.sampleResources.appspec as String)
        def taskdefTemplate = libraryResource(cfg.sampleResources.taskdef as String)

        // Optional token replacements (simple string replacement)
        // Example replacements map:
        // templateReplacements: [
        //   "PLACEHOLDER_TAG": "abc123",
        //   "us-east-1": "us-east-1"
        // ]
        (cfg.templateReplacements as Map).each { k, v ->
            appspecTemplate = appspecTemplate.replace(k.toString(), v.toString())
            taskdefTemplate = taskdefTemplate.replace(k.toString(), v.toString())
        }

        writeFile(file: cfg.artifacts.appspecPath, text: appspecTemplate)
        writeFile(file: cfg.artifacts.taskDefPath, text: taskdefTemplate)

        echo "Wrote templates to workspace:"
        echo " - ${cfg.artifacts.appspecPath}"
        echo " - ${cfg.artifacts.taskDefPath}"
    } else {
        echo "useSampleResources=false → expecting appspec/taskdef already exist in workspace"
        u.shAssert("test -f '${cfg.artifacts.appspecPath}'", "Missing ${cfg.artifacts.appspecPath} in workspace")
        u.shAssert("test -f '${cfg.artifacts.taskDefPath}'", "Missing ${cfg.artifacts.taskDefPath} in workspace")
    }

    // ------------------------------------------------------------
    // 2) Inject image into taskdef.json (if provided)
    // ------------------------------------------------------------
    if (cfg.image?.trim()) {
        echo "Injecting image into task definition: ${cfg.image}"
        def raw = readFile(cfg.artifacts.taskDefPath)
        def obj = (Map) new JsonSlurperClassic().parseText(raw)

        def containers = obj.containerDefinitions
        if (!(containers instanceof List) || containers.isEmpty()) {
            error "taskdef.json missing containerDefinitions[]"
        }

        def targetName = (cfg.containerName ?: "app").toString()
        def target = containers.find { it?.name?.toString() == targetName }

        if (!target) {
            // Fallback to first container if named one isn't present
            echo "WARN: containerName='${targetName}' not found. Falling back to first container definition."
            target = containers[0]
        }

        target.image = cfg.image.toString()

        writeFile(file: cfg.artifacts.taskDefPath, text: JsonOutput.prettyPrint(JsonOutput.toJson(obj)))
        echo "Updated taskdef.json container image successfully."
    } else {
        echo "No image provided → leaving taskdef.json image as-is."
    }

    // ------------------------------------------------------------
    // 3) Create a revision bundle (zip)
    // ------------------------------------------------------------
    def bundle = "codedeploy-revision-${env.JOB_NAME}-${env.BUILD_NUMBER}.zip"
            .replaceAll("[^A-Za-z0-9_.-]", "-")

    sh """
    rm -f '${bundle}'
    zip -r '${bundle}' '${cfg.artifacts.appspecPath}' '${cfg.artifacts.taskDefPath}'
    ls -lh '${bundle}'
  """.stripIndent()

    // ------------------------------------------------------------
    // 4) Deploy via CodeDeploy (S3 revision recommended)
    // ------------------------------------------------------------
    def region = cfg.aws.region.toString()
    def roleArn = (cfg.aws.roleArn ?: "").toString()

    def appName = cfg.codedeploy.applicationName.toString()
    def dgName = cfg.codedeploy.deploymentGroup.toString()

    // auto decide revision type if not set
    def revisionType = (cfg.revision.type ?: "").toString()
    if (!revisionType) {
        revisionType = (cfg.revision.bucket?.trim()) ? "s3" : "inline"
    }

    u.withAwsAssumeRole([region: region, roleArn: roleArn], {

        def deploymentId = ""

        if (revisionType == "s3") {
            u.requireKeys(cfg.revision as Map, ["bucket", "keyPrefix"])

            def bucket = cfg.revision.bucket.toString()
            def keyPrefix = cfg.revision.keyPrefix.toString().replaceAll("^/+", "").replaceAll("/+\$", "")
            def s3Key = "${keyPrefix}/${bundle}"

            echo "Uploading revision bundle to s3://${bucket}/${s3Key}"
            u.shAssert("aws s3 cp '${bundle}' 's3://${bucket}/${s3Key}'", "Failed to upload revision bundle to S3")

            echo "Creating CodeDeploy deployment using S3 revision..."
            deploymentId = u.shStdout("""
        aws deploy create-deployment \
          --application-name '${appName}' \
          --deployment-group-name '${dgName}' \
          --revision revisionType=S3,s3Location="{bucket=${bucket},key=${s3Key},bundleType=zip}" \
          --query 'deploymentId' --output text
      """.stripIndent())
        }
        else if (revisionType == "inline") {
            // Inline AppSpecContent is useful for demos, but most enterprises use S3 revisions.
            echo "Creating CodeDeploy deployment using INLINE AppSpecContent (demo-friendly)..."

            // Base64 AppSpec content for the create-deployment call
            def appspecB64 = u.shStdout("base64 < '${cfg.artifacts.appspecPath}' | tr -d '\\n'", true)
            def appspecSha = u.shStdout("sha256sum '${cfg.artifacts.appspecPath}' | awk '{print \$1}'", true)

            deploymentId = u.shStdout("""
        aws deploy create-deployment \
          --application-name '${appName}' \
          --deployment-group-name '${dgName}' \
          --revision revisionType=AppSpecContent,appSpecContent="{content=\\"${appspecB64}\\",sha256=\\"${appspecSha}\\"}" \
          --query 'deploymentId' --output text
      """.stripIndent())
        }
        else {
            error "Unsupported revision.type='${revisionType}'. Use 's3' or 'inline'."
        }

        echo "Deployment started. deploymentId=${deploymentId}"
        env.CODEDEPLOY_DEPLOYMENT_ID = deploymentId

        // Wait for completion
        u.waitUntilOrFail(30, 20, "CodeDeploy deployment to finish", {
            def status = u.shStdout(
                    "aws deploy get-deployment --deployment-id '${deploymentId}' --query 'deploymentInfo.status' --output text",
                    true
            )
            echo "CodeDeploy status=${status}"
            return (status in ["Succeeded", "Failed", "Stopped"])
        })

        def finalStatus = u.shStdout(
                "aws deploy get-deployment --deployment-id '${deploymentId}' --query 'deploymentInfo.status' --output text",
                true
        )

        if (finalStatus != "Succeeded") {
            def err = u.shStdout(
                    "aws deploy get-deployment --deployment-id '${deploymentId}' --query 'deploymentInfo.errorInformation.message' --output text 2>/dev/null || true",
                    true
            )
            error "❌ Deployment did not succeed. status=${finalStatus}. error=${err}"
        }

        echo "✅ ECS Blue/Green deployment succeeded."
    })
}
