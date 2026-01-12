# Sample Resources – How to Use

## ECS Blue/Green with CodeDeploy
These two files are meant to be delivered to CodeDeploy as a "revision":

- appspec.yaml
- taskdef.json

Your pipeline typically does:
1) Build + push image to ECR
2) Update taskdef.json with the new image (tag or digest)
3) Create a CodeDeploy deployment using the AppSpec + taskdef

### In this shared library
`deployEcsBlueGreen.groovy` expects:
- artifacts.appspecPath (default appspec.yaml)
- artifacts.taskDefPath (default taskdef.json)
- optional image: injected into taskdef.json

So you can:
- Copy these samples into your application repo root (appspec.yaml + taskdef.json)
  OR
- Keep them in your shared library `resources/sample/` and load them via `libraryResource`
  then write them to workspace.

## EKS / Helm
`helm-values.yaml` is an optional example if your org deploys to EKS using Helm.

Typical flow:
- Jenkins builds image, pushes to ECR
- Helm deploy uses values.yaml and sets image.tag dynamically
