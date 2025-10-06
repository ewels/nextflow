/*
 * Copyright 2013-2025, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.seqera.tower.plugin.cli

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.seqera.http.HxClient
import io.seqera.tower.plugin.TowerClient
import nextflow.BuildInfo
import nextflow.cli.CmdLaunch
import nextflow.cli.ColorUtil
import nextflow.config.ConfigBuilder
import nextflow.exception.AbortOperationException
import nextflow.file.FileHelper
import nextflow.scm.AssetManager
import org.yaml.snakeyaml.Yaml

import java.io.FileNotFoundException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * CLI sub-command LAUNCH -- Launch a workflow in Seqera Platform
 *
 * @author Phil Ewels <phil.ewels@seqera.io>
 */
@Slf4j
@CompileStatic
class LaunchCommandImpl implements CmdLaunch.LaunchCommand {

    static final public List<String> VALID_PARAMS_FILE = ['json', 'yml', 'yaml']
    static final private Pattern DOT_ESCAPED = ~/\\\./
    static final private Pattern DOT_NOT_ESCAPED = ~/(?<!\\)\./
    static final int API_TIMEOUT_MS = 10000

    // Delegate to AuthCommandImpl for shared authentication and API functionality
    private final AuthCommandImpl authHelper = new AuthCommandImpl()

    @Override
    void launch(CmdLaunch.LaunchOptions options) {
        printBanner(options)

        log.debug "Executing 'nextflow launch' command"

        final pipeline = options.pipeline
        log.debug "Pipeline repository: ${pipeline}"

        // Reject local file paths
        if (isLocalPath(pipeline)) {
            log.debug "Rejecting local file path: ${pipeline}"
            throw new AbortOperationException("Local file paths are not supported. Please provide a remote repository URL.")
        }

        // Resolve repository URL using AssetManager (same as nextflow run)
        String resolvedPipelineUrl = resolvePipelineUrl(pipeline)
        log.debug "Resolved pipeline URL: ${resolvedPipelineUrl}"

        // Load configuration to get authentication and endpoint
        final config = authHelper.readConfig()
        final apiEndpoint = (config['tower.endpoint'] ?: TowerClient.DEF_ENDPOINT_URL) as String
        final accessToken = config['tower.accessToken'] as String

        if (!accessToken) {
            throw new AbortOperationException("No authentication found. Please run 'nextflow auth login' first.")
        }

        // Get workspace info
        final workspaceId = resolveWorkspaceId(config, options.workspace, accessToken, apiEndpoint)
        final userName = getUserName(accessToken, apiEndpoint)

        String orgName = null
        String workspaceName = null
        if (workspaceId) {
            final wsDetails = getWorkspaceDetails(accessToken, apiEndpoint, workspaceId)
            orgName = wsDetails?.orgName as String
            workspaceName = wsDetails?.workspaceName as String
            log.debug "Using workspace '${workspaceName}' (ID: ${workspaceId})"
        } else {
            log.debug "Using personal workspace for user: ${userName}"
        }

        // Find compute environment
        log.debug "Looking up compute environment: ${options.computeEnv ?: '(primary)'}"
        def computeEnvInfo = findComputeEnv(options.computeEnv, workspaceId, accessToken, apiEndpoint)
        if (!computeEnvInfo) {
            if (options.computeEnv) {
                throw new AbortOperationException("Compute environment '${options.computeEnv}' not found")
            } else {
                throw new AbortOperationException("No primary compute environment found")
            }
        }
        String computeEnvId = computeEnvInfo.id as String

        log.debug "Using compute environment: '${computeEnvInfo.name}' (ID: ${computeEnvId})"

        // Use compute environment workDir if not specified on CLI
        String workDir = options.workDir
        if (!workDir && computeEnvInfo.workDir) {
            workDir = computeEnvInfo.workDir as String
        }
        if (!workDir) {
            throw new AbortOperationException("Work directory is required. Please specify -w/--work-dir or ensure your compute environment has a workDir configured.")
        }

        // Parse parameters
        log.debug "Building parameters from CLI args and params file"
        log.debug "CLI params provided: ${options.params}"
        log.debug "Params file: ${options.paramsFile ?: 'none'}"
        String paramsText = buildParamsText(options.params, options.paramsFile)
        if (paramsText) {
            log.debug "Generated params JSON (${paramsText.length()} chars)"
        } else {
            log.debug "No parameters specified"
        }

        // Build config text
        log.debug "Building configuration from config files"
        log.debug "Config files: ${options.configFiles ?: 'none'}"
        log.debug "Config profile: ${options.profile ?: 'none'}"
        String configText = buildConfigText(options.configFiles)
        if (configText) {
            log.debug "Generated config text (${configText.length()} chars)"
        } else {
            log.debug "No configuration files specified"
        }

        // Build launch request
        def launch = [:]
        launch.computeEnvId = computeEnvId
        launch.workDir = workDir
        if (options.runName) launch.runName = options.runName
        launch.pipeline = resolvedPipelineUrl
        if (options.revision) launch.revision = options.revision
        if (options.profile) launch.configProfiles = options.profile
        if (configText) launch.configText = configText
        if (paramsText) launch.paramsText = paramsText
        if (options.mainScript) launch.mainScript = options.mainScript
        if (options.entryName) launch.entryName = options.entryName
        launch.resume = options.resume != null
        launch.pullLatest = options.latest
        launch.stubRun = options.stubRun

        def launchRequest = [launch: launch]

        log.debug "Built launch request with ${launchRequest.launch.size()} parameters"

        // Submit launch request
        def queryParams = workspaceId ? [workspaceId: workspaceId.toString()] : [:]
        if (workspaceId) {
            log.debug "Submitting to workspace ID: ${workspaceId}"
        } else {
            log.debug "Submitting to personal workspace (no workspaceId specified)"
        }

        def response = apiPost('/workflow/launch', launchRequest, queryParams, accessToken, apiEndpoint)

        // Get workflow details to extract accurate launch information
        def workflowDetails = null
        if (response.workflowId) {
            log.debug "Fetching workflow details for ID: ${response.workflowId}"
            def workflowQueryParams = workspaceId ? [workspaceId: workspaceId.toString()] : [:]
            workflowDetails = apiGet("/workflow/${response.workflowId}", workflowQueryParams, accessToken, apiEndpoint)
        }

        // Extract launch information from workflow details
        def actualRunName = 'unknown'
        def commitId = 'unknown'
        def actualRevision = options.revision
        def actualRepo = resolvedPipelineUrl

        if (workflowDetails?.workflow) {
            def workflow = workflowDetails.workflow as Map
            actualRunName = workflow.runName as String ?: options.runName ?: 'unknown'
            commitId = workflow.commitId as String ?: 'unknown'
            actualRevision = workflow.revision as String ?: options.revision
        } else {
            // Fallback to original data if workflow details not available
            actualRunName = options.runName ?: 'unknown'
        }

        // Print launch info line
        printLaunchInfo(actualRepo, actualRunName, commitId, actualRevision, workDir, computeEnvInfo.name as String, userName, orgName, workspaceName, options)

        // Construct and display tracking URL
        def trackingUrl = null
        final webUrl = authHelper.getWebUrlFromApiEndpoint(apiEndpoint)
        if (response.workflowId && orgName && workspaceName) {
            trackingUrl = "${webUrl}/orgs/${orgName}/workspaces/${workspaceName}/watch/${response.workflowId}/"
        } else if (response.workflowId && userName) {
            trackingUrl = "${webUrl}/user/${userName}/watch/${response.workflowId}/"
        }

        printSuccessMessage(response.workflowId as String, trackingUrl, options)

        // Poll for log messages if we have a workflow ID
        if (response.workflowId) {
            pollWorkflowLogs(response.workflowId as String, workspaceId, trackingUrl, accessToken, apiEndpoint, options)
        }
    }

    protected void printBanner(CmdLaunch.LaunchOptions options) {
        if (ColorUtil.isAnsiEnabled()) {
            // Plain header for verbose log
            log.debug "N E X T F L O W  ~  version ${BuildInfo.version}"

            // Fancy coloured header for the console output
            println ""
            // Use exact colour codes matching the Nextflow green brand
            final BACKGROUND = "\033[1m\033[38;5;232m\033[48;5;43m"
            print("$BACKGROUND N E X T F L O W ")
            print("\033[0m")

            // Show Nextflow version and launch context
            print(ColorUtil.colorize("  ~  ", "dim", true))
            println("version " + BuildInfo.version)
            println("Launching workflow in Seqera Platform")
            println ""
        } else {
            // Plain header to the console if ANSI is disabled
            log.info "N E X T F L O W  ~  version ${BuildInfo.version}"
            log.info "Launching workflow in Seqera Platform"
        }
    }

    protected void printLaunchInfo(String repo, String runName, String commitId, String revision, String workDir, String computeEnvName, String userName, String orgName, String workspaceName, CmdLaunch.LaunchOptions options) {
        def showRevision = commitId && commitId != 'unknown'
        def showRevisionBrackets = revision && revision != 'unknown'

        if (ColorUtil.isAnsiEnabled()) {
            def debugMsg = "Launched `${repo}` [${runName}]"
            if (showRevision) {
                debugMsg += " - revision: ${commitId}"
            }
            if (showRevisionBrackets) {
                debugMsg += " [${revision}]"
            }
            log.debug debugMsg

            print("Launched")
            print(ColorUtil.colorize(" `$repo` ", "magenta", true))
            print(ColorUtil.colorize("[", "dim", true))
            print(ColorUtil.colorize(runName, "cyan bold", true))
            print(ColorUtil.colorize("]", "dim", true))
            if (showRevision) {
                print(" - ")
                print(ColorUtil.colorize("revision: $commitId", "cyan", true))
            }
            if (showRevisionBrackets) {
                print(ColorUtil.colorize(" [", "dim", true))
                print(ColorUtil.colorize(revision, "cyan", true))
                print(ColorUtil.colorize("]", "dim", true))
            }
            println ""

            // Print username
            if (userName) {
                println(" 👤 user: ${ColorUtil.colorize(userName, 'cyan', true)}")
            }

            // Print workspace
            if (orgName && workspaceName) {
                println(" 🏢 workspace: ${ColorUtil.colorize(orgName + ' / ' + workspaceName, 'cyan', true)}")
            } else {
                println(" 🏢 workspace: ${ColorUtil.colorize("Personal workspace", 'cyan', true)}")
            }

            // Print work directory
            println(" 📁 workdir: ${ColorUtil.colorize(workDir, 'cyan', true)}")

            // Print compute environment
            println(" ☁️ compute: ${ColorUtil.colorize(computeEnvName, 'cyan', true)}\n")
        } else {
            def plainMsg = "Launched `${repo}` [${runName}]"
            if (showRevision) {
                plainMsg += " - revision: ${commitId}"
            }
            if (showRevisionBrackets) {
                plainMsg += " [${revision}]"
            }
            log.info plainMsg
            if (userName) {
                log.info " user: ${userName}"
            }
            if (orgName && workspaceName) {
                log.info " workspace: ${orgName} / ${workspaceName}"
            } else {
                log.info " workspace: Personal workspace"
            }
            log.info " workdir: ${workDir}"
            log.info " compute: ${computeEnvName}"
        }
    }

    protected void printSuccessMessage(String workflowId, String trackingUrl, CmdLaunch.LaunchOptions options) {
        if (ColorUtil.isAnsiEnabled()) {
            if (trackingUrl) {
                print(ColorUtil.colorize("Workflow launched successfully: ", "green"))
                ColorUtil.printColored(trackingUrl, "cyan")
            } else if (workflowId) {
                ColorUtil.printColored("Workflow launched successfully!", "green")
                print("Workflow ID: ")
                ColorUtil.printColored(workflowId, "cyan")
            } else {
                ColorUtil.printColored("Workflow launched successfully!", "green")
            }
            print(ColorUtil.colorize("💡 To do more with Seqera Platform via the CLI, see ", "dim"))
            ColorUtil.printColored("https://github.com/seqeralabs/tower-cli/", "cyan bold")
            println ""
        } else {
            if (trackingUrl) {
                log.info "Workflow launched successfully: ${trackingUrl}"
            } else if (workflowId) {
                log.info "Workflow launched successfully!"
                log.info "Workflow ID: ${workflowId}"
            } else {
                log.info "Workflow launched successfully!"
            }
            log.info "To do more with Seqera Platform via the CLI, see https://github.com/seqeralabs/tower-cli/"
        }
    }

    /**
     * Poll workflow logs until the workflow completes
     */
    private void pollWorkflowLogs(String workflowId, Long workspaceId, String trackingUrl, String accessToken, String apiEndpoint, CmdLaunch.LaunchOptions options) {
        log.debug "Starting log polling for workflow ID: ${workflowId}"

        def queryParams = workspaceId ? [workspaceId: workspaceId.toString()] : [:]
        def displayedLogCount = 0
        def finalStatuses = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN'] as Set<String>
        def firstLogReceived = false
        def workflowFinished = false
        def workflowFinishedTime = 0L

        // Flag to signal when to stop polling
        def shouldExit = new AtomicBoolean(false)

        // Add shutdown hook to handle Ctrl+C
        def shutdownHook = new Thread({
            shouldExit.set(true)
            log.debug "Shutdown hook triggered - setting exit flag"

            // Print exit message
            if (ColorUtil.isAnsiEnabled()) {
                println ""
                println "Exiting log viewer."
                if (trackingUrl) {
                    print(ColorUtil.colorize("⚠️ Workflow is still running in Seqera Platform: ", "yellow bold"))
                    ColorUtil.printColored(trackingUrl, "cyan")
                }
            } else {
                log.info "Exiting log viewer."
                if (trackingUrl) {
                    log.info "Workflow is still running in Seqera Platform: ${trackingUrl}"
                }
            }
        })

        Runtime.getRuntime().addShutdownHook(shutdownHook)

        // Initial message
        if (ColorUtil.isAnsiEnabled()) {
            print(ColorUtil.colorize("Workflow submitted, awaiting log output. It is now safe to exit with ctrl+c", "dim"))
        } else {
            log.info "Workflow submitted, awaiting log output. It is now safe to exit with ctrl+c"
        }

        try {
            while (!shouldExit.get()) {
                try {
                    // Check workflow status
                    def workflowResponse = apiGet("/workflow/${workflowId}", queryParams, accessToken, apiEndpoint)
                    def workflow = workflowResponse.workflow as Map
                    def status = workflow?.status as String

                    log.debug "Workflow status: ${status}"

                    // Poll the log endpoint for new entries
                    def logResponse = apiGet("/workflow/${workflowId}/log", queryParams, accessToken, apiEndpoint)
                    def logData = logResponse.log as Map

                    if (logData) {
                        def entries = logData.entries as List<String>

                        // Display new log entries
                        if (entries && entries.size() > displayedLogCount) {
                            // Clear waiting message on first log entry
                            if (!firstLogReceived) {
                                firstLogReceived = true
                                if (ColorUtil.isAnsiEnabled()) {
                                    println ""
                                    print('─' * 10)
                                    println ""
                                }
                            }

                            def newEntries = entries.subList(displayedLogCount, entries.size())
                            for (String entry : newEntries) {
                                if (shouldExit.get()) break
                                println entry
                            }
                            displayedLogCount = entries.size()
                        } else if (!firstLogReceived) {
                            // Show progress dots while waiting
                            if (ColorUtil.isAnsiEnabled()) {
                                print(ColorUtil.colorize(".", "dim", true))
                            }
                            System.out.flush()
                        }
                    }

                    // Check if workflow has reached a final status
                    if (status && finalStatuses.contains(status)) {
                        if (!workflowFinished) {
                            log.debug "Workflow reached final status: ${status}, continuing to poll for 5 more seconds to capture remaining logs"
                            workflowFinished = true
                            workflowFinishedTime = System.currentTimeMillis()
                        }
                    }

                    // Check if we should stop polling (5 seconds after workflow finished)
                    if (workflowFinished && (System.currentTimeMillis() - workflowFinishedTime) > 5000) {
                        log.debug "Grace period elapsed, stopping log polling"
                        break
                    }

                    // Wait 2 seconds before next API poll
                    for (int i = 0; i < 20 && !shouldExit.get(); i++) {
                        Thread.sleep(100)
                    }

                } catch (Exception e) {
                    if (shouldExit.get()) break
                    log.error "Error polling workflow logs: ${e.message}", e
                    // Continue polling despite errors (wait 2 seconds)
                    for (int i = 0; i < 20 && !shouldExit.get(); i++) {
                        Thread.sleep(100)
                    }
                }
            }
        } finally {
            // Remove shutdown hook if we exit normally
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            } catch (IllegalStateException e) {
                // Shutdown hook already executed, ignore
            }
        }

        log.debug "Log polling completed for workflow ID: ${workflowId}"
    }

    private static boolean isLocalPath(String path) {
        if (path.startsWith('/') || path.startsWith('./') || path.startsWith('../')) {
            return true
        }
        if (path.contains('\\') || path ==~ /^[A-Za-z]:.*/) {
            return true
        }
        return false
    }

    /**
     * Convert API endpoint URL to web UI URL
     * Examples:
     *   https://api.cloud.seqera.io -> https://cloud.seqera.io
     *   https://api.cloud.stage-seqera.io -> https://cloud.stage-seqera.io
     *   https://tower.example.com/api -> https://tower.example.com
     */
    private String buildParamsText(Map<String, String> params, String paramsFile) {
        final result = [:]

        // Apply params file
        if (paramsFile) {
            log.debug "Processing params file: ${paramsFile}"
            def path = validateParamsFile(paramsFile)
            def type = path.extension.toLowerCase() ?: null
            if (type == 'json') {
                log.debug "Reading JSON params file: ${paramsFile}"
                readJsonFile(path, result)
            } else if (type == 'yml' || type == 'yaml') {
                log.debug "Reading YAML params file: ${paramsFile}"
                readYamlFile(path, result)
            }
            log.debug "Loaded ${result.size()} parameters from file"
        }

        // Apply CLI params
        if (params) {
            log.debug "Processing ${params.size()} CLI parameters"
            for (Map.Entry<String, String> entry : params) {
                log.trace "Adding parameter: ${entry.key} = ${entry.value}"
                addParam(result, entry.key, entry.value)
            }
            log.debug "Total parameters after CLI merge: ${result.size()}"
        }

        if (result.isEmpty()) {
            log.debug "No parameters to include in launch request"
            return null
        }

        // Convert to JSON
        def jsonBuilder = new JsonBuilder(result)
        def jsonText = jsonBuilder.toString()
        log.debug "Converted ${result.size()} parameters to JSON format"
        return jsonText
    }

    private String buildConfigText(List<String> configFiles) {
        if (!configFiles || configFiles.isEmpty()) {
            log.debug "No configuration files specified"
            return null
        }

        log.debug "Processing ${configFiles.size()} configuration files"
        def configText = new StringBuilder()
        for (String configFile : configFiles) {
            log.debug "Reading config file: ${configFile}"
            def path = FileHelper.asPath(configFile)
            if (!path.exists()) {
                log.debug "Config file not found: ${configFile}"
                throw new AbortOperationException("Config file not found: ${configFile}")
            }
            def content = path.text
            log.debug "Read ${content.length()} characters from config file: ${configFile}"
            configText.append(content).append('\n')
        }

        log.debug "Combined configuration text: ${configText.length()} characters"
        return configText.toString()
    }

    private Path validateParamsFile(String file) {
        def result = FileHelper.asPath(file)
        def ext = result.getExtension()
        if (!VALID_PARAMS_FILE.contains(ext)) {
            throw new AbortOperationException("Not a valid params file extension: $file -- It must be one of the following: ${VALID_PARAMS_FILE.join(',')}")
        }
        return result
    }

    private void readJsonFile(Path file, Map result) {
        try {
            def json = (Map<String, Object>) new JsonSlurper().parseText(file.text)
            json.forEach((name, value) -> {
                addParam0(result, name, value)
            })
        } catch (NoSuchFileException | FileNotFoundException e) {
            throw new AbortOperationException("Specified params file does not exist: ${file.toUriString()}")
        } catch (Exception e) {
            throw new AbortOperationException("Cannot parse params file: ${file.toUriString()} - Cause: ${e.message}", e)
        }
    }

    private void readYamlFile(Path file, Map result) {
        try {
            def yaml = (Map<String, Object>) new Yaml().load(file.text)
            yaml.forEach((name, value) -> {
                addParam0(result, name, value)
            })
        } catch (NoSuchFileException | FileNotFoundException e) {
            throw new AbortOperationException("Specified params file does not exist: ${file.toUriString()}")
        } catch (Exception e) {
            throw new AbortOperationException("Cannot parse params file: ${file.toUriString()}", e)
        }
    }

    private static void addParam(Map params, String key, String value, List path = [], String fullKey = null) {
        if (!fullKey) {
            fullKey = key
        }
        final m = DOT_NOT_ESCAPED.matcher(key)
        if (m.find()) {
            final p = m.start()
            final root = key.substring(0, p)
            if (!root) throw new AbortOperationException("Invalid parameter name: $fullKey")
            path.add(root)
            def nested = params.get(root)
            if (nested == null) {
                nested = new LinkedHashMap<>()
                params.put(root, nested)
            } else if (!(nested instanceof Map)) {
                log.warn "Command line parameter --${path.join('.')} is overwritten by --${fullKey}"
                nested = new LinkedHashMap<>()
                params.put(root, nested)
            }
            addParam((Map) nested, key.substring(p + 1), value, path, fullKey)
        } else {
            addParam0(params, key.replaceAll(DOT_ESCAPED, '.'), parseParamValue(value))
        }
    }

    private static void addParam0(Map params, String key, Object value) {
        if (key.contains('-')) {
            key = kebabToCamelCase(key)
        }
        params.put(key, value)
    }

    private static String kebabToCamelCase(String str) {
        final result = new StringBuilder()
        str.split('-').eachWithIndex { String entry, int i ->
            result << (i > 0 ? entry.capitalize() : entry)
        }
        return result.toString()
    }

    private static Object parseParamValue(String str) {
        if (str == null) return null

        if (str.toLowerCase() == 'true') return Boolean.TRUE
        if (str.toLowerCase() == 'false') return Boolean.FALSE

        if (str ==~ /-?\d+(\.\d+)?/ && str.isInteger()) return str.toInteger()
        if (str ==~ /-?\d+(\.\d+)?/ && str.isLong()) return str.toLong()
        if (str ==~ /-?\d+(\.\d+)?/ && str.isDouble()) return str.toDouble()

        return str
    }

    /**
     * Resolve pipeline name to full repository URL using AssetManager
     */
    private String resolvePipelineUrl(String pipelineName) {
        try {
            log.debug "Resolving pipeline name using AssetManager: ${pipelineName}"
            def assetManager = new AssetManager(pipelineName)
            def repositoryUrl = assetManager.getRepositoryUrl()
            if (repositoryUrl) {
                log.debug "AssetManager resolved URL: ${repositoryUrl}"
                return repositoryUrl
            } else {
                log.debug "AssetManager could not resolve URL, using original name: ${pipelineName}"
                return pipelineName
            }
        } catch (Exception e) {
            log.debug "Failed to resolve pipeline URL with AssetManager: ${e.message}, using original name: ${pipelineName}"
            return pipelineName
        }
    }

    // ===== API Helper Methods =====

    protected Map apiGet(String path, Map queryParams = [:], String accessToken, String apiEndpoint) {
        final url = buildUrl(apiEndpoint, path, queryParams)
        final client = authHelper.createHttpClient(accessToken)
        final request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build()

        final response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            final error = response.body() ?: "HTTP ${response.statusCode()}"
            throw new RuntimeException("API request failed: ${error}")
        }

        return new JsonSlurper().parseText(response.body()) as Map
    }

    protected Map apiPost(String path, Map body, Map queryParams = [:], String accessToken, String apiEndpoint) {
        final url = buildUrl(apiEndpoint, path, queryParams)
        final requestBody = new JsonBuilder(body).toString()
        final client = authHelper.createHttpClient(accessToken)
        final request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header('Content-Type', 'application/json')
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        final response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            final error = response.body() ?: "HTTP ${response.statusCode()}"
            throw new RuntimeException("Failed to launch workflow: ${error}")
        }

        return new JsonSlurper().parseText(response.body()) as Map
    }

    private String buildUrl(String endpoint, String path, Map queryParams) {
        def url = new StringBuilder(endpoint)
        if (!path.startsWith('/')) {
            url.append('/')
        }
        url.append(path)

        if (queryParams && !queryParams.isEmpty()) {
            url.append('?')
            url.append(queryParams.collect { k, v -> "${URLEncoder.encode(k.toString(), 'UTF-8')}=${URLEncoder.encode(v.toString(), 'UTF-8')}" }.join('&'))
        }

        return url.toString()
    }

    private Long resolveWorkspaceId(Map config, String workspaceName, String accessToken, String apiEndpoint) {
        // First check config for workspace ID
        final configWorkspaceId = config['tower.workspaceId']
        if (configWorkspaceId) {
            return configWorkspaceId as Long
        }

        // If workspace name provided, look it up
        if (workspaceName) {
            final userId = getUserId(accessToken, apiEndpoint)
            final workspaces = authHelper.getUserWorkspaces(accessToken, apiEndpoint, userId)

            final matchingWorkspace = workspaces.find { workspace ->
                final ws = workspace as Map
                final wsName = ws.workspaceName as String
                wsName == workspaceName
            }

            if (!matchingWorkspace) {
                throw new AbortOperationException("Workspace '${workspaceName}' not found")
            }

            return (matchingWorkspace as Map).workspaceId as Long
        }

        return null
    }

    private String getUserName(String accessToken, String apiEndpoint) {
        final userInfo = authHelper.callUserInfoApi(accessToken, apiEndpoint)
        return userInfo.userName as String
    }

    private String getUserId(String accessToken, String apiEndpoint) {
        final userInfo = authHelper.callUserInfoApi(accessToken, apiEndpoint)
        return userInfo.id as String
    }

    private Map getWorkspaceDetails(String accessToken, String apiEndpoint, Long workspaceId) {
        return authHelper.getWorkspaceDetailsFromApi(accessToken, apiEndpoint, workspaceId.toString())
    }

    private Map findComputeEnv(String computeEnvName, Long workspaceId, String accessToken, String apiEndpoint) {
        final computeEnvs = getComputeEnvs(workspaceId, accessToken, apiEndpoint)

        log.debug "Looking for ${computeEnvName ? "compute environment with name: ${computeEnvName}" : "primary compute environment"} ${workspaceId ? "in workspace ID ${workspaceId}" : "in personal workspace"}"

        for (item in computeEnvs) {
            final computeEnv = item as Map
            if ((computeEnvName && computeEnv.name == computeEnvName) || (!computeEnvName && computeEnv.primary == true)) {
                log.debug "Found ${computeEnvName ? "matching" : "primary"} compute environment '${computeEnv.name}' (ID: ${computeEnv.id})"
                return computeEnv
            }
        }

        return null
    }

    private List getComputeEnvs(Long workspaceId, String accessToken, String apiEndpoint) {
        def path = workspaceId ? "/compute-envs?workspaceId=${workspaceId}" : "/compute-envs"

        final client = authHelper.createHttpClient(accessToken)
        final request = HttpRequest.newBuilder()
            .uri(URI.create("${apiEndpoint}${path}"))
            .GET()
            .build()

        final response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            final error = response.body() ?: "HTTP ${response.statusCode()}"
            throw new RuntimeException("Failed to get compute environments: ${error}")
        }

        final json = new JsonSlurper().parseText(response.body()) as Map
        return json.computeEnvs as List ?: []
    }
}
