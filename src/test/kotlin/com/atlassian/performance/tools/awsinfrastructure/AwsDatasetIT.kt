package com.atlassian.performance.tools.awsinfrastructure

import com.amazonaws.regions.Regions
import com.atlassian.performance.tools.aws.api.StorageLocation
import com.atlassian.performance.tools.awsinfrastructure.IntegrationTestRuntime.aws
import com.atlassian.performance.tools.awsinfrastructure.IntegrationTestRuntime.logContext
import com.atlassian.performance.tools.awsinfrastructure.IntegrationTestRuntime.taskWorkspace
import com.atlassian.performance.tools.awsinfrastructure.api.DatasetCatalogue
import com.atlassian.performance.tools.awsinfrastructure.api.Infrastructure
import com.atlassian.performance.tools.infrastructure.api.dataset.Dataset
import com.atlassian.performance.tools.ssh.api.Ssh
import com.mashape.unirest.http.HttpResponse
import com.mashape.unirest.http.Unirest
import org.apache.logging.log4j.Logger
import org.json.JSONObject
import org.junit.Test
import java.net.URI
import java.time.Duration.ofMinutes
import java.util.*

class AwsDatasetIT {

    private val logger: Logger = logContext.getLogger(this::class.java.canonicalName)
    private val workspace = taskWorkspace.isolateTest(javaClass.simpleName)
    private val sourceDataset = DatasetCatalogue().custom(StorageLocation(URI("s3://jpt-custom-datasets-storage-a008820-datasetbucket-1sjxdtrv5hdhj/dataset-dd3c3aa7-ca8e-4537-8045-ba575e7b3130"), com.amazonaws.regions.Regions.EU_WEST_1))

    @Test
    fun shouldRemoveBackups() {
        AwsDataset(sourceDataset)
            .modify(
                aws = aws,
                workspace = workspace,
                newDatasetName = "dataset-${UUID.randomUUID()}"
            ) { infrastructure ->
                val jiraHome = infrastructure.jira.jiraHome
                val backupPath = "${jiraHome.location}/export"
                Ssh(jiraHome.host, connectivityPatience = 4)
                    .newConnection()
                    .use { ssh ->
                        val listCommand = "ls -lh $backupPath"
                        val listOutput = ssh.execute(listCommand).output
                        println("$ $listCommand\n$listOutput")
                        ssh.execute("rm -r $backupPath")
                    }
            }
    }

    private fun smallJiraSeven(): Dataset = DatasetCatalogue().custom(
        location = StorageLocation(
            URI("s3://jpt-custom-datasets-storage-a008820-datasetbucket-1sjxdtrv5hdhj/")
                .resolve("af4c7d3b-925c-464c-ab13-79f615158316"),
            Regions.EU_WEST_1
        ),
        label = "7k issues",
        databaseDownload = ofMinutes(5),
        jiraHomeDownload = ofMinutes(5)
    )

    @Test
    fun shouldArchiveVersions() {
        AwsDataset(sourceDataset)
            .modify(
                aws = aws,
                workspace = workspace,
                newDatasetName = "dataset-${UUID.randomUUID()}"
            ) { infrastructure ->
                archiveVersions(infrastructure)
            }
    }

    private fun archiveVersions(
        infrastructure: Infrastructure<*>
    ) {
        val desiredArchiveRatio = (3400.0 / 4000.0)..(3600.0 / 4000.0)
        val jira = infrastructure
            .jira
            .address
        val rest = jira.resolve("rest/api/2/")
        val projectIds = rest
            .resolve("project")
            .let { Unirest.get(it.toString()) }
            .basicAuth("admin", "admin")
            .asJson()
            .expectOk()
            .body
            .array
            .map { it as JSONObject }
            .map { it.getString("id") }
        var fixedProjects = 0
        val projectsToFix = projectIds.size
        for (projectId in projectIds) {
            fixProject(projectId, desiredArchiveRatio, rest)
            fixedProjects++
            logger.info("$fixedProjects/$projectsToFix projects fixed")
        }
    }

    private fun fixProject(
        projectId: String,
        archiveRatioTarget: ClosedRange<Double>,
        rest: URI
    ) {
        val versions = rest
            .resolve("project/$projectId/versions")
            .let { Unirest.get(it.toString()) }
            .basicAuth("admin", "admin")
            .asJson()
            .expectOk()
            .body
            .array
            .map { it as JSONObject }
        var archivedCount = versions
            .filter { it.isArchived() }
            .size
        val totalCount = versions.size
        for (version in versions) {
            val versionId = version.getString("id")
            val archiveRatio = archivedCount.toDouble() / totalCount
            if (archiveRatio in archiveRatioTarget) {
                break
            } else if (archiveRatio > archiveRatioTarget.endInclusive) {
                if (version.isArchived()) {
                    update(versionId, false, rest)
                    archivedCount--
                }
            } else {
                if (version.isArchived().not()) {
                    update(versionId, true, rest)
                    archivedCount++
                }
            }
        }
    }

    private fun update(
        versionId: String,
        archived: Boolean,
        rest: URI
    ) {
        val version = JSONObject(mapOf(
            "id" to versionId,
            "archived" to archived
        ))
        rest
            .resolve("version/$versionId")
            .let { Unirest.put(it.toString()) }
            .basicAuth("admin", "admin")
            .header("Content-Type", "application/json")
            .body(version)
            .asJson()
            .expectOk()
    }

    private fun <T> HttpResponse<T>.expectOk(): HttpResponse<T> {
        if (status != 200) {
            throw Exception("Expected a HTTP 200, but got HTTP $status, headers: $headers, body: $body")
        }
        return this
    }

    private fun JSONObject.isArchived(): Boolean = getBoolean("archived")
}
