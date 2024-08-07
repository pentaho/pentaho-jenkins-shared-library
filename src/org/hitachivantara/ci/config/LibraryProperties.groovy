/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.config

class LibraryProperties implements Serializable {

  //**********************
  //** Build Properties **
  //**********************
  public static final String PRIVATE_RELEASE_REPO_URL = 'PRIVATE_RELEASE_REPO_URL'
  public static final String PRIVATE_SNAPSHOT_REPO_URL = 'PRIVATE_SNAPSHOT_REPO_URL'
  public static final String PUBLIC_RELEASE_REPO_URL = 'PUBLIC_RELEASE_REPO_URL'
  public static final String PUBLIC_SNAPSHOT_REPO_URL = 'PUBLIC_SNAPSHOT_REPO_URL'
  public static final String RESOLVE_REPO_URL = 'RESOLVE_REPO_URL'
  public static final String OPTS = 'OPTS'
  public static final String SETTINGS = 'SETTINGS'
  public static final String DEFAULT_DIRECTIVES = 'DEFAULT_DIRECTIVES'
  public static final String DEFAULT_COMMAND_OPTIONS = 'DEFAULT_COMMAND_OPTIONS'


  // TODO refactor to remove fixed builder properties for generic ones
  public static final String ANT_DEFAULT_COMMAND_OPTIONS = 'ANT_DEFAULT_COMMAND_OPTIONS'
  public static final String ANT_DEFAULT_DIRECTIVES = 'ANT_DEFAULT_DIRECTIVES'
  public static final String ANT_TEST_TARGETS = 'ANT_TEST_TARGETS'

  public static final String GRADLE_DEFAULT_COMMAND_OPTIONS = 'GRADLE_DEFAULT_COMMAND_OPTIONS'
  public static final String GRADLE_DEFAULT_DIRECTIVES = 'GRADLE_DEFAULT_DIRECTIVES'
  public static final String GRADLE_TEST_TARGETS = 'GRADLE_TEST_TARGETS'

  public static final String MAVEN_TEST_OPTS = 'MAVEN_TEST_OPTS'

  public static final String JENKINS_JDK_FOR_BUILDS = 'JENKINS_JDK_FOR_BUILDS'
  public static final String JENKINS_ANT_FOR_BUILDS = 'JENKINS_ANT_FOR_BUILDS'
  public static final String JENKINS_GRADLE_FOR_BUILDS = 'JENKINS_GRADLE_FOR_BUILDS'
  public static final String JENKINS_MAVEN_FOR_BUILDS = 'JENKINS_MAVEN_FOR_BUILDS'



  //***************
  //** Archiving **
  //***************
  public static final String ARCHIVE_ARTIFACTS = 'ARCHIVE_ARTIFACTS'
  public static final String ARCHIVING_EXCLUDE_PATTERN = "ARCHIVING_EXCLUDE_PATTERN"
  public static final String ARCHIVING_CONFIG = 'ARCHIVING_CONFIG'
  public static final String ARCHIVING_PATH_GROUP_REGEX = 'ARCHIVING_PATH_GROUP_REGEX'
  public static final String ARCHIVE_TO_JENKINS_MASTER = 'ARCHIVE_TO_JENKINS_MASTER'


  //***********************************
  //** General build path properties **
  //***********************************
  public static final String BUILD_CONFIG_ROOT_PATH = 'BUILD_CONFIG_ROOT_PATH'
  public static final String BUILDS_ROOT_PATH = 'BUILDS_ROOT_PATH'
  public static final String BUILD_DATA_ROOT_PATH = 'BUILD_DATA_ROOT_PATH'
  public static final String BUILD_HOSTING_ROOT = 'BUILD_HOSTING_ROOT'
  public static final String BUILD_DATA_FILE = 'BUILD_DATA_FILE'
  public static final String DEFAULT_BUILD_PROPERTIES = 'DEFAULT_BUILD_PROPERTIES'
  public static final String BUILD_VERSIONS_FILE = 'BUILD_VERSIONS_FILE'
  public static final String LIB_CACHE_ROOT_PATH = 'LIB_CACHE_ROOT_PATH'
  public static final String BUILD_DATA_YAML = 'BUILD_DATA_YAML'


  //******************************
  //** General build properties **
  //******************************
  public static final String BUILD_PLAN_ID = 'BUILD_PLAN_ID'
  public static final String BUILD_RETRIES = 'BUILD_RETRIES'
  public static final String BUILD_TIMEOUT = 'BUILD_TIMEOUT'
  public static final String BUILD_ID_TAIL = 'BUILD_ID_TAIL'

  public static final String BUILD_ORDER_REPORT = 'BUILD_ORDER_REPORT'

  public static final String NOOP = 'NOOP'

  // TODO deprecate the stage ones
  public static final String RUN_BUILDS = 'RUN_BUILDS'
  public static final String RUN_CHECKOUTS = 'RUN_CHECKOUTS'
  public static final String RUN_VERSIONING = 'RUN_VERSIONING'
  public static final String RUN_UNIT_TESTS = 'RUN_UNIT_TESTS'
  public static final String RUN_AUDIT = 'RUN_AUDIT'
  public static final String RUN_NEXUS_LIFECYCLE = 'RUN_NEXUS_LIFECYCLE'
  public static final String RUN_DEPENDENCY_CHECK = 'RUN_DEPENDENCY_CHECK'
  public static final String RUN_SONARQUBE = 'RUN_SONARQUBE'
  public static final String RUN_FROGBOT = 'RUN_FROGBOT'

  public static final String PUSH_CHANGES = 'PUSH_CHANGES'
  public static final String PARALLEL_SIZE = 'PARALLEL_SIZE'
  public static final String PARALLEL_CHUNKSIZE = 'PARALLEL_CHUNKSIZE'
  public static final String PARALLEL_CHECKOUT_CHUNKSIZE = 'PARALLEL_CHECKOUT_CHUNKSIZE'
  public static final String PARALLEL_PUSH_CHUNKSIZE = 'PARALLEL_PUSH_CHUNKSIZE'
  public static final String PARALLEL_TAG_CHUNKSIZE = 'PARALLEL_TAG_CHUNKSIZE'
  public static final String PARALLEL_UNIT_TESTS_CHUNKSIZE = 'PARALLEL_UNIT_TESTS_CHUNKSIZE'
  public static final String PARALLEL_SECURITY_SCAN_CHUNKSIZE = 'PARALLEL_SECURITY_SCAN_CHUNKSIZE'
  public static final String PARALLEL_ARCHIVING_CHUNKSIZE = 'PARALLEL_ARCHIVING_CHUNKSIZE'

  public static final String CLEAN_ALL_CACHES = 'CLEAN_ALL_CACHES'
  public static final String CLEAN_BUILD_WORKSPACE = 'CLEAN_BUILD_WORKSPACE'
  public static final String CLEAN_CACHES_REGEX = 'CLEAN_CACHES_REGEX'
  public static final String CLEAN_SCM_WORKSPACES = 'CLEAN_SCM_WORKSPACES'
  public static final String CLEAN_MINION_DATA = 'CLEAN_MINION_DATA'

  //*********
  //** VCS **
  //*********
  public static final String CHECKOUT_TIMEOUT_MINUTES = 'CHECKOUT_TIMEOUT_MINUTES'
  public static final String CHECKOUT_DEPTH = 'CHECKOUT_DEPTH'
  public static final String SHALLOW_CLONE = 'SHALLOW_CLONE'


  //**********************************
  //** Properties for Docker builds **
  //**********************************
  public static final String DOCKER_IMAGE_HOST = 'DOCKER_IMAGE_HOST'
  public static final String DOCKER_REGISTRY_URL = 'DOCKER_REGISTRY_URL'
  public static final String DOCKER_RESOLVE_REPO = 'DOCKER_RESOLVE_REPO'
  public static final String DOCKER_PUBLIC_PUSH_REPO = 'DOCKER_PUBLIC_PUSH_REPO'
  public static final String DOCKER_PRIVATE_PUSH_REPO = 'DOCKER_PRIVATE_PUSH_REPO'

  //**********************************
  //** Properties for additional Maven options **
  //**********************************
  public static final String NODEJS_BUNDLE_REPO_URL = 'NODEJS_BUNDLE_REPO_URL'
  public static final String NPM_RELEASE_REPO_URL = 'NPM_RELEASE_REPO_URL'

  //******************
  //**  Credentials **
  //******************
  public static final String ARTIFACT_DEPLOYER_CREDENTIALS_ID = 'ARTIFACT_DEPLOYER_CREDENTIALS_ID'
  public static final String SLACK_CREDENTIALS_ID = 'SLACK_CREDENTIALS_ID'
  public static final String SCM_CREDENTIALS_ID = 'SCM_CREDENTIALS_ID'
  public static final String SCM_API_TOKEN_CREDENTIALS_ID = 'SCM_API_TOKEN_CREDENTIALS_ID'


  //*******************
  //** logging level **
  //*******************
  public static final String LOG_LEVEL = 'LOG_LEVEL'


  //********************
  //** version merger **
  //********************
  public static final String VERSION_MERGER_VERSION = 'VERSION_MERGER_VERSION'
  public static final String VERSION_MERGER_LOG_LEVEL = 'VERSION_MERGER_LOG_LEVEL'


  //********************
  //** Stage Labeling **
  //********************
  public static final String STAGE_LABEL_CONFIGURE = 'Configure'
  public static final String STAGE_LABEL_PRECLEAN = 'Pre Clean'
  public static final String STAGE_LABEL_CHECKOUT = 'Checkout'
  public static final String STAGE_LABEL_VERSIONING = 'Version'
  public static final String STAGE_LABEL_BUILD = 'Build'
  public static final String STAGE_LABEL_AUDIT = 'Scans'
  public static final String STAGE_LABEL_UNIT_TEST = 'Test'
  public static final String STAGE_LABEL_PUSH = 'Push'
  public static final String STAGE_LABEL_TAG = 'Tag'
  public static final String STAGE_LABEL_ARCHIVING = 'Archive'
  public static final String STAGE_LABEL_SECURITY = 'Security'
  public static final String STAGE_LABEL_POSTCLEAN = 'Post Clean'
  public static final String STAGE_LABEL_REPORT = 'Report'
  public static final String STAGE_LABEL_PRE_BUILD = 'Pre Build'
  public static final String STAGE_LABEL_POST_BUILD = 'Post Build'
  public static final String STAGE_LABEL_COLLECT_JOB_DATA = 'Collect Job Data'

  //*******************
  //** Pull Requests **
  //*******************
  public static final String PR_STATUS_REPORTS = 'PR_STATUS_REPORTS'
  public static final String PR_SLACK_CHANNEL = 'PR_SLACK_CHANNEL'

  //***************
  //** Got tired **
  //***************
  public static final String BUILD_NUMBER = 'BUILD_NUMBER'

  public static final String RELEASE_MODE = 'RELEASE_MODE'
  public static final String RELEASE_VERSION = 'RELEASE_VERSION'
  public static final String RELEASE_BUILD_NUMBER = 'RELEASE_BUILD_NUMBER'

  public static final String VERSION_MESSAGE = 'VERSION_MESSAGE'
  public static final String VERSION_COMMIT_FILES = 'VERSION_COMMIT_FILES'

  public static final String SLAVE_NODE_LABEL = 'SLAVE_NODE_LABEL'
  public static final String DEPLOYMENT_FOLDER = 'DEPLOYMENT_FOLDER'

  public static final String CREATE_TAG = 'CREATE_TAG'
  public static final String TAG_NAME = 'TAG_NAME'
  public static final String TAG_MESSAGE = 'TAG_MESSAGE'
  public static final String TAG_SKIP_SNAPSHOT = 'TAG_SKIP_SNAPSHOT'
  public static final String TICKET_MANAGER_URL = 'TICKET_MANAGER_URL'
  public static final String TICKET_ID_PATTERN = 'TICKET_ID_PATTERN'

  public static final String WORKSPACE = 'WORKSPACE'
  public static final String STAGE_NAME = 'STAGE_NAME'
  public static final String BRANCH_NAME = 'BRANCH_NAME'
  public static final String CHANGE_ID = 'CHANGE_ID'
  public static final String CHANGE_TARGET = 'CHANGE_TARGET'

  public static final String IGNORE_PIPELINE_FAILURE = 'IGNORE_PIPELINE_FAILURE'
  public static final String SLACK_INTEGRATION = 'SLACK_INTEGRATION'
  public static final String SLACK_CHANNEL = 'SLACK_CHANNEL'
  public static final String SLACK_CHANNEL_SUCCESS = 'SLACK_CHANNEL_SUCCESS'
  public static final String SLACK_TEAM_DOMAIN = 'SLACK_TEAM_DOMAIN'
  public static final String UNMAPPED_DEPENDENCIES_FAIL_BUILD = "UNMAPPED_DEPENDENCIES_FAIL_BUILD"
  public static final String NEXUS_IQ_STAGE = 'NEXUS_IQ_STAGE'
  public static final String VULNERABILITY_DATABASE_PATH = 'VULNERABILITY_DATABASE_PATH'
  public static final String DEPENDENCY_CHECK_SCAN_PATTERN = 'DEPENDENCY_CHECK_SCAN_PATTERN'
  public static final String DEPENDENCY_CHECK_SUPPRESSION_PATH = 'DEPENDENCY_CHECK_SUPPRESSION_PATH'
  public static final String DEPENDENCY_CHECK_REPORT_PATTERN = 'DEPENDENCY_CHECK_REPORT_PATTERN'
  public static final String CHANGES_FROM_LAST = 'CHANGES_FROM_LAST'
  public static final String POLL_CRON_INTERVAL = 'POLL_CRON_INTERVAL'
  public static final String FIRST_JOB = 'FIRST_JOB'
  public static final String LAST_JOB = 'LAST_JOB'
  public static final String OVERRIDE_JOB_PARAMS = 'OVERRIDE_JOB_PARAMS'
  public static final String OVERRIDE_PARAMS = 'OVERRIDE_PARAMS'
  public static final String JOB_ITEM_DEFAULTS = 'JOB_ITEM_DEFAULTS'

  //public static final String LIB_VERSION = 'LIB_VERSION'
  //public static final String LIB_NAME = 'LIB_NAME'

  public static final String USE_MINION_JOBS = 'USE_MINION_JOBS'
  public static final String USE_MINION_MULTIBRANCH_JOBS = 'USE_MINION_MULTIBRANCH_JOBS'
  public static final String JOB_NAME = 'JOB_NAME'
  public static final String MINION_PIPELINE_TEMPLATE = 'MINION_PIPELINE_TEMPLATE'
  public static final String MINION_BUILD_DATA_ROOT_PATH = 'MINION_BUILD_DATA_ROOT_PATH'
  public static final String IS_MINION = 'IS_MINION'
  public static final String IS_MULTIBRANCH_MINION = 'IS_MULTIBRANCH_MINION'
  public static final String MINIONS_FOLDER = 'MINIONS_FOLDER'
  public static final String MINION_POLL_CRON_INTERVAL = 'MINION_POLL_CRON_INTERVAL'
  public static final String MINION_LOGS_TO_KEEP = 'MINION_LOGS_TO_KEEP'

  public static final String LOGS_TO_KEEP = 'LOGS_TO_KEEP'
  public static final String ARTIFACTS_TO_KEEP = 'ARTIFACTS_TO_KEEP'
  public static final String DISABLE_CONCURRENT_BUILDS = 'DISABLE_CONCURRENT_BUILDS'

  public static final String ARTIFACTORY_BASE_URL = 'ARTIFACTORY_BASE_URL'
  public static final String FROGBOT_PATH_EXCLUSIONS = 'FROGBOT_PATH_EXCLUSIONS'

}
