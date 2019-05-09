package com.atlassian.performance.tools.awsinfrastructure

import com.amazonaws.regions.Regions
import com.atlassian.performance.tools.aws.api.StorageLocation
import com.atlassian.performance.tools.awsinfrastructure.IntegrationTestRuntime.aws
import com.atlassian.performance.tools.awsinfrastructure.IntegrationTestRuntime.taskWorkspace
import com.atlassian.performance.tools.awsinfrastructure.api.DatasetCatalogue
import com.atlassian.performance.tools.awsinfrastructure.api.Infrastructure
import com.atlassian.performance.tools.infrastructure.api.dataset.Dataset
import com.atlassian.performance.tools.ssh.api.Ssh
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPut
import org.apache.http.entity.ContentType
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.junit.Test
import java.net.URI
import java.time.Duration.ofMinutes
import java.util.*
import javax.json.Json
import javax.json.JsonObject
import javax.json.JsonValue

class AwsDatasetIT {

    private val logger: Logger = LogManager.getLogger(this::class.java)
    private val workspace = taskWorkspace.isolateTest(javaClass.simpleName)
    private val sourceDataset = smallJiraSeven()

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
        val rest = infrastructure
            .jira
            .address
            .resolve("rest/api/2/")
        val http = HttpClientBuilder.create()
            .setDefaultCredentialsProvider(
                BasicCredentialsProvider().apply {
                    setCredentials(
                        AuthScope.ANY,
                        UsernamePasswordCredentials("admin", "admin")
                    )
                }
            )
            .build()
        val projectIds = rest
            .resolve("project")
            .let { HttpGet(it) }
            .let { http.execute(it) }
            .entity
            .content
            .let { Json.createReader(it) }
            .readArray()
            .map { it.asJsonObject() }
            .map { it.getString("id") }
        var fixedProjects = 0
        val projectsToFix = projectIds.size
        for (projectId in projectIds) {
            fixProject(projectId, desiredArchiveRatio, rest, http)
            fixedProjects++
            logger.info("$fixedProjects/$projectsToFix projects fixed")
        }
    }

    private fun fixProject(
        projectId: String,
        archiveRatioTarget: ClosedRange<Double>,
        rest: URI,
        http: HttpClient
    ) {
        val versions = rest
            .resolve("project/$projectId/versions")
            .let { HttpGet(it) }
            .let { http.execute(it) }
            .entity
            .content
            .let { Json.createReader(it) }
            .readArray()
            .map { it.asJsonObject() }
        var archivedCount = versions
            .filter { it.isArchived() }
            .size
        val totalCount = versions.size
        for (version in versions) {
            val archiveRatio = archivedCount.toDouble() / totalCount
            if (archiveRatio in archiveRatioTarget) {
                break
            } else if (archiveRatio > archiveRatioTarget.endInclusive) {
                if (version.isArchived()) {
                    val unarchived = version.setArchived(false)
                    update(unarchived, rest, http)
                    archivedCount--
                }
            } else {
                if (version.isArchived().not()) {
                    val archived = version.setArchived(true)
                    update(archived, rest, http)
                    archivedCount++
                }
            }
        }
    }

    private fun update(
        version: JsonObject,
        rest: URI,
        http: HttpClient
    ) {
        val versionId = version.getString("id")
        rest
            .resolve("version/$versionId")
            .let { HttpPut(it) }
            .apply { entity = StringEntity(version.toString(), ContentType.APPLICATION_JSON) }
            .let { http.execute(it) }
    }

    private fun JsonObject.isArchived(): Boolean = getBoolean("archived")

    private fun JsonObject.setArchived(
        archived: Boolean
    ): JsonObject = Json.createObjectBuilder(this)
        .add("archived", if (archived) JsonValue.TRUE else JsonValue.FALSE)
        .build()
}
