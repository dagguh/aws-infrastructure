package com.atlassian.performance.tools.awsinfrastructure.api

import com.amazonaws.regions.Regions
import com.atlassian.performance.tools.aws.api.Investment
import com.atlassian.performance.tools.aws.api.StorageLocation
import com.atlassian.performance.tools.awsinfrastructure.IntegrationTestRuntime.aws
import com.atlassian.performance.tools.awsinfrastructure.IntegrationTestRuntime.taskWorkspace
import com.atlassian.performance.tools.awsinfrastructure.api.dataset.DatasetHost
import com.atlassian.performance.tools.awsinfrastructure.api.hardware.C5NineExtraLargeEphemeral
import com.atlassian.performance.tools.awsinfrastructure.api.jira.StandaloneFormula
import com.atlassian.performance.tools.awsinfrastructure.api.virtualusers.AbsentVirtualUsersFormula
import com.atlassian.performance.tools.infrastructure.api.dataset.Dataset
import com.atlassian.performance.tools.infrastructure.api.distribution.PublicJiraSoftwareDistribution
import com.atlassian.performance.tools.infrastructure.api.jira.JiraNodeConfig
import com.atlassian.performance.tools.infrastructure.api.splunk.AtlassianSplunkForwarder
import com.atlassian.performance.tools.ssh.api.Ssh
import org.junit.Test
import java.net.URI
import java.time.Duration.ofMinutes
import java.util.function.Consumer

class AwsDatasetModificationIT {

    private val workspace = taskWorkspace.isolateTest(javaClass.simpleName)
    private val sourceDataset = smallJiraSeven()
    private val host = DatasetHost { dataset ->
        InfrastructureFormula(
            investment = Investment(
                useCase = "Generic purpose dataset modification",
                lifespan = ofMinutes(50)
            ),
            jiraFormula = StandaloneFormula.Builder(
                database = dataset.database,
                jiraHomeSource = dataset.jiraHomeSource,
                productDistribution = PublicJiraSoftwareDistribution("7.2.0")
            )
                .computer(C5NineExtraLargeEphemeral())
                .config(
                    JiraNodeConfig.Builder()
                        .splunkForwarder(AtlassianSplunkForwarder(emptyMap(), "TODO"))
                        .build()
                )
                .build(),
            virtualUsersFormula = AbsentVirtualUsersFormula(),
            aws = aws
        )
    }

    @Test
    fun shouldRemoveBackups() {
        val transformation = Consumer<Infrastructure<*>> { infrastructure ->
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
        val modification = AwsDatasetModification.Builder(
            aws = aws,
            dataset = sourceDataset
        )
            .host(host)
            .workspace(workspace)
            .onlineTransformation(transformation)
            .build()

        modification.modify()
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
}
