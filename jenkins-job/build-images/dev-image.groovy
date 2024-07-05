@Library('jenkins-shared-library') _

def pod = libraryResource 'io/milvus/pod/tekton-3.yaml'
def output = [:]

pipeline {
    options {
        skipDefaultCheckout true
    }
    parameters {
        string(
            description: 'replace with your own repo owner',
            name: 'repo_owner',
            defaultValue: 'milvus-io'
        )
        string(
            description: 'branch to build',
            name: 'branch',
            defaultValue: 'master'
        )
        booleanParam(
            description: 'Debug mode if checked.',
            name: 'Debug',
            defaultValue: false
        )
    }
    agent {
        kubernetes {
            yaml pod
        }
    }
    stages {
        stage('build') {
            steps {
                container('kubectl') {
                    script {
                        if (Debug == 'true') {
                            build_type = 'debug'
                        } else {
                            build_type = 'release'
                        }

                        job_name = tekton.run revision: "${branch}",
                                                            arch:'amd64',
                                                            repo_url: "https://github.com/${repo_owner}/milvus.git",
                                                            build_type: build_type,
                                                            milvus_repo_owner: "${repo_owner}"
                    }
                }

                container('tkn') {
                    script {
                        try {
                           tekton.print_log(job_name)
                        } catch (Exception e) {
                            println e
                        }
                        (ok, ret) = tekton.check_result(job_name)
                        if (!ok) {
                            error(ret)
                        }

                        println ret
                    }
                }
            }
        }

        stage('archive result') {
            steps {
                container('jnpl') {
                    script {
                        output.image = ret

                        writeJSON(file: 'output.json', json: output)
                        archiveArtifacts artifacts: 'output.json', onlyIfSuccessful: true
                    }
                }
            }
        }
    }
}