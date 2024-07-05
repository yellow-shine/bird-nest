
def run_tekton_pipeline(Map args) {

    def getGcc = { revision, gcc ->
        if (revision.startsWith('v2.3') || revision.startsWith('2.3')) {
            'gcc9'
        } else {
            gcc ?: 'gcc12'
        }
    }
   

    def getOSName = { revision, osName ->
        if (revision.startsWith('v2.3') || revision.startsWith('2.3')) {
            'ubuntu20.04'
        } else {
            osName ?: ''
        }
    }

    def part_of_arm_template = '''
  taskRunTemplate:
    podTemplate:
      tolerations:
      - key: "node-role.kubernetes.io/arm"
        operator: "Exists"
        effect: "NoSchedule"
      nodeSelector:
        "kubernetes.io/arch": "arm64"
'''

    def template = """
cat << EOF | kubectl create -f -
apiVersion: tekton.dev/v1
kind: PipelineRun
metadata:
  generateName: milvus-build-
  namespace: milvus-ci
spec:
  pipelineRef:
    name: milvus-clone-build-push
  taskRunSpecs:
    - pipelineTaskName: push-image
      serviceAccountName: robot-tekton
    - pipelineTaskName: sync-env-image
      serviceAccountName: robot-tekton
${ args.arch == 'arm64' ? part_of_arm_template : '' }
  workspaces:
  - name: shared-data
    volumeClaimTemplate:
      spec:
        storageClassName: local-path
        accessModes:
        - ReadWriteOnce
        resources:
          requests:
            storage: 100Gi
  params:
  - name: revision
    value: ${args.revision}
  - name: arch
    value: ${args.arch}
  - name: computing_engine
    value: ${args.computing_engine ?: 'cpu'}
  - name: build_type
    value: ${args.build_type == 'debug' ? 'debug' : 'release'}
  - name: repo_url
    value: ${args.repo_url ?: 'https://github.com/milvus-io/milvus.git'}
  - name: milvus_repo_owner
    value: ${args.milvus_repo_owner ?: 'milvus-io'}
  - name: suppress_suffix_of_image_tag
    # if revision starts with 'v' (means tag), then image tag should be end with arch(amd64, arm64)
    # otherwise, arch suffix could be suppressed
    value: ${args.revision.startsWith('v') ? false : (args.suppress_suffix_of_image_tag ?: 'false') }
  - name: os_name
    value: ${getOSName(args.revision, args.os_name)}
  - name: milvus_env_version
    value: ${args.milvus_env_version ?: ''}
  - name: gcc
    value: ${getGcc(args.revision, args.gcc)}

EOF
"""

    println template

    def ret = sh( label: 'tekton pipeline run', script: template, returnStdout: true)

    name = ret.split('/')[1].split(' ')[0]
    return name
}

def print_log(name) {
    sh """
     timeout 1h tkn pipelinerun logs ${name} -f -n milvus-ci
  """
}

// return [true,""] if there is no failures
// return [false, error message] if there is a failure
def check_result(name) {
    def ret = sh( label: 'tekton pipelinerun describe', script: "tkn pipelinerun describe ${name} -n milvus-ci -o yaml", returnStdout: true)

    println ret

    def read = readYaml text: ret
    // mean failed if there is a condition with type Succeeded and status False
    def failures = read.status.conditions.findAll { it.type == 'Succeeded' && it.status == 'False' }

    if (failures.size() > 0) {
        return [false, failures[0].message]
    }

    def results = read.status.results.findAll { it.name == 'image' }

    if (results.size() == 0) {
        return [true, 'No image result found']
    }

    def image = results[0].value

    return [ true, image]
}
