import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

def call(String rootPath = '.') {
  def GIT_USER_NAME = "Jenkins"
  def GIT_USER_EMAIL = "jenkins@vodafone.com"

  def TRIPELA_LIBS_GROUP_ID = "com.vodafone.triplea"

  def TRIPLEA_GIT_REPO_CRED_ID = 'triplea-git-server'
  def MAVEN_PUBLIC_REPO_CRED_ID = 'stock-public-maven'
  def MAVEN_STOCK_REPO_CRED_ID = 'stock-nexus-maven'

  def DOCKER_REGISTRY_CREDENTIALS_ID = 'stock-nexus-default'
  def DOCKER_REGISTRY_URL = '172.24.100.50:4411'

  def DEVOPS_FILES = [
    [source: '/maven/settings.xml', target: '.mvn/settings.xml'],
  ]

  def ROOT_PATH = rootPath
  def REVISION = "1.0." + (env.BUILD_NUMBER ?: "0-SNAPSHOT")
  def BRANCH_NAME = ""

  pipeline {

    agent {
      docker {
        image "${DOCKER_REGISTRY_URL}/devops/maven-git:latest"
        registryUrl "http://${DOCKER_REGISTRY_URL}"
        registryCredentialsId "${DOCKER_REGISTRY_CREDENTIALS_ID}"
      }
    }

    stages {
      stage('CHECKOUT') {
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
        steps {
          dir(ROOT_PATH) {
            script {
              def commitID = sh(returnStdout: true, script: "git log -n 1 --pretty=format:%h").trim()
              def datetime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"))

              echo "INFO: BRANCH_NAME: ${BRANCH_NAME}"
              echo "INFO: REVISION: ${REVISION}"
              echo "INFO: COMMIT_ID: ${commitID}"
            }
          }
        }
      }

      stage("BUILD & DEPLOY") {
        environment {
          PUBLIC_MAVEN = credentials("${MAVEN_PUBLIC_REPO_CRED_ID}")
          STOCK_MAVEN = credentials("${MAVEN_STOCK_REPO_CRED_ID}")
          TRIPLEA_GIT = credentials("${TRIPLEA_GIT_REPO_CRED_ID}")
        }
        steps {
          script {
            dir(ROOT_PATH) {
              // maven insecure repository hatasini engellemek icin tum komutlardaki default parametreler
              def maven_args = "-Dmaven.test.skip=true -Dmaven.wagon.http.ssl.insecure=true -Dmaven.wagon.http.ssl.allowall=true -Dmaven.wagon.http.ssl.ignore.validity.dates=true"
              // repoda cache'lenmis hatali modulleri varsa temizle
              //sh "mvn ${maven_args} -Drevision=${REVISION} -s .mvn/settings.xml dependency:purge-local-repository -DactTransitively=false -DreResilve=false --fail-at-end"
              // triplea kutuplanelerini guncelle
              //sh "mvn  ${maven_args} -Drevision=${REVISION} -s .mvn/settings.xml versions:use-latest-releases -Dincludes=${TRIPELA_LIBS_GROUP_ID}"
              //sh "mvn  ${maven_args} -Drevision=${REVISION} -s .mvn/settings.xml dependency:resolve -Dincludes=${TRIPELA_LIBS_GROUP_ID}"
              // build ve deploy islemi
              sh "mvn  ${maven_args} -Dtag=${REVISION} -Drevision=${REVISION} -U -s .mvn/settings.xml clean verify deploy scm:tag"
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
