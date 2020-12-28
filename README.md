# Jenkins Pipeline Shared Library

This project is a Jenkins Pipeline Shared Library, which can be called in Jenkins pipelines.

##Setup instructions:

1. In Jenkins, go to Manage Jenkins → Configure System. Under Global Pipeline Libraries, add a library with the following settings:
    * Library name: jenkins-shared-library
    * Default version: Specify a Git reference (branch or tag or commit SHA), e.g. master
    * Retrieval method: Modern SCM
    * Select the Git type
    * Project repository: https://github.com/mvvbapiraju/jenkins-shared-library.git
    * Credentials: Use credential for private repository

2. Then create a Jenkins job with the following pipeline (note that the underscore _ is not a typo):

    ```
    @Library('jenkins-shared-library')_
    ```
    OR
    ```
    library identifier: 'mylibraryname@master',
        //'master' refers to a valid git-ref
        //'mylibraryname' can be any name you like
        retriever: modernSCM([
          $class: 'GitSCMSource',
          credentialsId: 'your-credentials-id',
          remote: 'https://git.yourcompany.com/yourrepo/private-library.git'
    ])
    ```

