import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

def call(String rootPath = '.') {
  def GIT_USER_NAME = 'Jenkins'
  def GIT_USER_EMAIL = 'jenkins@vodafone.com'

  def TRIPLEA_GIT_REPO_CRED_ID = 'triplea-git-server'
  def MAVEN_PUBLIC_REPO_CRED_ID = 'stock-public-maven'
  def MAVEN_STOCK_REPO_CRED_ID = 'stock-nexus-maven'

  def DOCKER_REGISTRY_CREDENTIALS_ID = 'stock-nexus-default'
  def DOCKER_REGISTRY_URL = '172.24.100.50:4411'

  def SONAR_REGISTRY_URL = 'http://10.86.30.81:8085'
  def SONAR_PROJECT_NAME = 'STOCK'
  def SONAR_EXCLUSIONS = ''

  def SONAR_ANALYSIS = false
  def FORTY_ANALYSIS = false

  def DEVOPS_FILES = [
    [source: '/docker/Dockerfile', target: 'Dockerfile'],
    [source: '/docker/app-env.sh', target: 'app-env.sh'],
    [source: '/docker/app-start.sh', target: 'app-start.sh'],
    [source: '/docker/.dockerignore', target: '.dockerignore'],
    [source: '/maven/settings.xml', target: '.mvn/settings.xml'],
  ]

  def ROOT_PATH = rootPath
  def FULL_IMAGE_NAME = ""
  def BRANCH_NAME = ""
  def IMAGE_TAG = ""
  def PROPS = []

  pipeline {

    agent any

    stages {
      stage('CHECKOUT') {
        agent {
          docker {
            image "${DOCKER_REGISTRY_URL}/devops/maven-git:latest"
            registryUrl "http://${DOCKER_REGISTRY_URL}"
            registryCredentialsId "${DOCKER_REGISTRY_CREDENTIALS_ID}"
          }
        }
        steps {
          script {
            dir(ROOT_PATH) {
              BRANCH_NAME = scm.branches[0].name
              withCredentials([gitUsernamePassword(credentialsId: TRIPLEA_GIT_REPO_CRED_ID)]) {
                // git push icin kullanici ayari
                sh "git config user.email '${GIT_USER_EMAIL}'"
                sh "git config user.name '${GIT_USER_NAME}'"
                // hata sonucu push edilememis taglari resetlemek icin kontrol
                sh "git tag | xargs git tag -d"
                // hata sonucu push edilememis commitleri resetlemek icin kontrol
                sh "git clean -f && git reset --hard origin/${BRANCH_NAME}"

                // Script dosyalarini kopyalar
                DEVOPS_FILES.each { f ->
                  writeFile file: f.target, text: libraryResource(f.source)
                }
              }
            }
          }
        }
      }

      stage('SET ENV') {
        agent {
          docker {
            image "${DOCKER_REGISTRY_URL}/devops/maven-git:latest"
            registryUrl "http://${DOCKER_REGISTRY_URL}"
            registryCredentialsId "${DOCKER_REGISTRY_CREDENTIALS_ID}"
          }
        }
        steps {
          dir(ROOT_PATH) {
            script {
              def commitID = sh(returnStdout: true, script: "git log -n 1 --pretty=format:%h").trim()
              def datetime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"))
              def configYaml = "jenkins-${BRANCH_NAME}.yaml"

              if (fileExists(configYaml)) {
                PROPS = readYaml file: configYaml
              } else {
                error "ERROR: Expected config file '${configYaml}' does not exists in branch: ${BRANCH_NAME}"
              }

              FULL_IMAGE_NAME = "${DOCKER_REGISTRY_URL}/${PROPS.IMAGE_GROUP}/${PROPS.IMAGE_NAME}"
              IMAGE_TAG = "${BRANCH_NAME}-${datetime}-${commitID}"

              echo "INFO: PROPS ${PROPS}"
              echo "INFO: BRANCH_NAME: ${BRANCH_NAME}"
              echo "INFO: COMMIT_ID: ${commitID}"
              echo "INFO: DOCKER_REGISTRY_URL: ${DOCKER_REGISTRY_URL}"
              echo "INFO: FULL_IMAGE_NAME: ${FULL_IMAGE_NAME}"
              echo "INFO: IMAGE_TAG: ${IMAGE_TAG}"
            }
          }
        }
      }

      stage("COMPILE CODE") {
        agent {
          docker {
            image "${DOCKER_REGISTRY_URL}/devops/maven-git:latest"
            registryUrl "http://${DOCKER_REGISTRY_URL}"
            registryCredentialsId "${DOCKER_REGISTRY_CREDENTIALS_ID}"
          }
        }
        environment {
          PUBLIC_MAVEN = credentials("${MAVEN_PUBLIC_REPO_CRED_ID}")
          STOCK_MAVEN = credentials("${MAVEN_STOCK_REPO_CRED_ID}")
          TRIPLEA_GIT = credentials("${TRIPLEA_GIT_REPO_CRED_ID}")
        }
        steps {
          dir(ROOT_PATH) {
            script {
              // maven insecure repository hatasini engellemek icin tum komutlardaki default parametreler
              def maven_args = "-Dmaven.test.skip=true -Dmaven.wagon.http.ssl.insecure=true -Dmaven.wagon.http.ssl.allowall=true -Dmaven.wagon.http.ssl.ignore.validity.dates=true"
              // repoda cache'lenmis hatali modulleri varsa temizle
              //sh "mvn ${maven_args} -s .mvn/settings.xml dependency:purge-local-repository --fail-at-end"
              // triplea kutuplanelerini guncelle
              //sh "mvn  ${maven_args} -s .mvn/settings.xml versions:use-latest-releases -Dincludes=${TRIPELA_LIBS_GROUP_ID}"
              //sh "mvn  ${maven_args} -s .mvn/settings.xml dependency:resolve -Dincludes=${TRIPELA_LIBS_GROUP_ID}"
              // build ve deploy islemi
              sh "mvn ${maven_args} -Dtag=${IMAGE_TAG} -s .mvn/settings.xml -U clean verify scm:tag"
            }
          }
        }
      }

      stage("SONAR ANALYSIS") {
        agent {
          docker {
            image "${DOCKER_REGISTRY_URL}/devops/maven-git:latest"
            registryUrl "http://${DOCKER_REGISTRY_URL}"
            registryCredentialsId "${DOCKER_REGISTRY_CREDENTIALS_ID}"
          }
        }
        environment {
          PUBLIC_MAVEN = credentials("${MAVEN_PUBLIC_REPO_CRED_ID}")
          STOCK_MAVEN = credentials("${MAVEN_STOCK_REPO_CRED_ID}")
          TRIPLEA_GIT = credentials("${TRIPLEA_GIT_REPO_CRED_ID}")
          SONAR_USER_HOME = "./.sonar"
        }
        steps {
          dir(ROOT_PATH) {
            script {
              if (SONAR_ANALYSIS) {
                sh """
                  mvn -s .mvn/settings.xml \\
                  sonar:sonar \\
                  -Dsonar.host.url=${SONAR_REGISTRY_URL} \\
                  -Dsonar.login=${PUBLIC_MAVEN_USR} \\
                  -Dsonar.password=${PUBLIC_MAVEN_PSW} \\
                  -Dsonar.projectName=${SONAR_PROJECT_NAME}-${PROPS.IMAGE_NAME} \\
                  -Dsonar.exclusions=${SONAR_EXCLUSIONS} \\
                  -f pom.xml
                """
              } else {
                echo "INFO: SONAR_ANALYSIS disabled."
              }
            }
          }
        }
      }

      stage('FORTYFY ANALYSIS') {
        steps {
          dir(ROOT_PATH) {
            script {
              if (FORTY_ANALYSIS) {
                build job: 'Fortyfy Job', parameters: [
                  string(name: 'micro_service', value: PROPS.IMAGE_NAME),
                  booleanParam(name: 'is_build_maven', value: true)
                ]
              } else {
                echo "INFO: FORTY_ANALYSIS disabled."
              }
            }
          }
        }
      }

      stage("BUILD IMAGE") {
        agent {
          docker {
            image "${DOCKER_REGISTRY_URL}/devops/docker:19"
            registryUrl "http://${DOCKER_REGISTRY_URL}"
            registryCredentialsId "${DOCKER_REGISTRY_CREDENTIALS_ID}"
            args '-v /var/run/docker.sock:/var/run/docker.sock'
          }
        }
        environment {
          DOCKER_REGISTRY = credentials("${DOCKER_REGISTRY_CREDENTIALS_ID}")
          DOCKER_CONFIG = "."
          APP_PACKAGE_NAME = "${PROPS.IMAGE_NAME}-*.jar"
        }
        steps {
          sh "docker login -u ${DOCKER_REGISTRY_USR} -p ${DOCKER_REGISTRY_PSW} ${DOCKER_REGISTRY_URL}"
          sh "docker build --build-arg APP_PACKAGE_NAME=${APP_PACKAGE_NAME} -t ${PROPS.IMAGE_NAME}:${IMAGE_TAG} ${ROOT_PATH} "
        }
      }

      stage("PUSH IMAGE") {
        agent {
          docker {
            image "${DOCKER_REGISTRY_URL}/devops/docker:19"
            registryUrl "http://${DOCKER_REGISTRY_URL}"
            registryCredentialsId "${DOCKER_REGISTRY_CREDENTIALS_ID}"
            args '-v /var/run/docker.sock:/var/run/docker.sock'
          }
        }
        environment {
          DOCKER_REGISTRY = credentials("${DOCKER_REGISTRY_CREDENTIALS_ID}")
          DOCKER_CONFIG = "."
        }
        steps {
          sh "docker login -u ${DOCKER_REGISTRY_USR} -p ${DOCKER_REGISTRY_PSW} ${DOCKER_REGISTRY_URL}"

          sh "docker tag ${PROPS.IMAGE_NAME}:${IMAGE_TAG} ${FULL_IMAGE_NAME}:${IMAGE_TAG}"
          sh "docker tag ${PROPS.IMAGE_NAME}:${IMAGE_TAG} ${FULL_IMAGE_NAME}:${BRANCH_NAME}-latest"

          sh "docker push ${FULL_IMAGE_NAME}:${IMAGE_TAG}"
          sh "docker push ${FULL_IMAGE_NAME}:${BRANCH_NAME}-latest"
        }
      }

      stage("DEPLOY") {
        agent {
          docker {
            image "${DOCKER_REGISTRY_URL}/devops/quay.io/openshift/origin-cli:4.3"
            registryUrl "http://${DOCKER_REGISTRY_URL}"
            registryCredentialsId "${DOCKER_REGISTRY_CREDENTIALS_ID}"
          }
        }
        environment {
          HOME = "."
        }
        steps {
          script {
            for (OC in PROPS.OPENSHIFT) {
              withCredentials([
                usernamePassword(
                  credentialsId: "${OC.CREDENTIALS_ID}",
                  usernameVariable: 'OC_SERVICE_ACCOUNT',
                  passwordVariable: 'OC_TOKEN'
                )
              ]) {
                echo "INFO: Deploying on Datacenter: ${OC.NAME}"

                sh "oc login --insecure-skip-tls-verify --token=${OC_TOKEN} --server=${OC.API_URL}"
                sh "oc project ${OC.NAMESPACE}"
                sh "oc set image dc/${OC.DEPLOYMENT_CONFIG} ${OC.DEPLOYMENT_CONFIG}=${FULL_IMAGE_NAME}:${IMAGE_TAG}"
                sh "oc logout"
              }
            }
          }
        }
      }
    }
    post {
      failure {
        mail to: 'retailcapabilityandstockmanagement.tr@vodafone.com',
          subject: "Status: ${env.JOB_NAME} #${env.BUILD_NUMBER} : ${currentBuild.result}",
          body: "${env.BUILD_URL} has result ${currentBuild.result}"
      }
      always {
        influxDbPublisher customPrefix: '', customProjectName: '', jenkinsEnvParameterField: '', jenkinsEnvParameterTag: '', selectedTarget: 'InfluxDB'
      }
    }
  }
}
