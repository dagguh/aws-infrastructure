package com.atlassian.performance.tools.awsinfrastructure.api

import com.amazonaws.regions.Regions
import com.atlassian.performance.tools.aws.api.Investment
import com.atlassian.performance.tools.aws.api.StorageLocation
import com.atlassian.performance.tools.awsinfrastructure.IntegrationTestRuntime.aws
import com.atlassian.performance.tools.awsinfrastructure.IntegrationTestRuntime.taskWorkspace
import com.atlassian.performance.tools.awsinfrastructure.S3DatasetPackage
import com.atlassian.performance.tools.awsinfrastructure.api.dataset.DatasetHost
import com.atlassian.performance.tools.awsinfrastructure.api.hardware.C5NineExtraLargeEphemeral
import com.atlassian.performance.tools.awsinfrastructure.api.hardware.Volume
import com.atlassian.performance.tools.awsinfrastructure.api.jira.StandaloneFormula
import com.atlassian.performance.tools.awsinfrastructure.api.virtualusers.AbsentVirtualUsersFormula
import com.atlassian.performance.tools.infrastructure.api.database.MySqlDatabase
import com.atlassian.performance.tools.infrastructure.api.dataset.Dataset
import com.atlassian.performance.tools.infrastructure.api.distribution.PublicJiraSoftwareDistribution
import com.atlassian.performance.tools.infrastructure.api.jira.JiraHomePackage
import org.junit.Test
import java.net.URI
import java.time.Duration.ofMinutes
import java.util.function.Consumer

class AwsDatasetModificationIT {

    private val workspace = taskWorkspace.isolateTest(javaClass.simpleName)
    private val sourceDataset = StorageLocation(
        uri = URI("s3://jpt-custom-mysql-xl/dataset-7m-jira7"),
        region = Regions.EU_WEST_1
    ).let { location ->
        Dataset(
            label = "7M issues JSW 7 MySQL",
            database = MySqlDatabase(
                source = S3DatasetPackage(
                    artifactName = "database.tar.bz2",
                    location = location,
                    unpackedPath = "database",
                    downloadTimeout = ofMinutes(55)
                )
            ),
            jiraHomeSource = JiraHomePackage(
                S3DatasetPackage(
                    artifactName = "jirahome.tar.bz2",
                    location = location,
                    unpackedPath = "jirahome",
                    downloadTimeout = ofMinutes(55)
                )
            )
        )
    }

    @Test
    fun shouldRemoveBackups() {
        val transformation = Consumer<Infrastructure<*>> { infrastructure ->
        }
        val modification = AwsDatasetModification.Builder(
            aws = aws,
            dataset = sourceDataset
        )
            .workspace(workspace)
            .onlineTransformation(transformation)
            .host(DatasetHost {
                InfrastructureFormula(
                    investment = Investment(
                        useCase = "Generic purpose dataset modification",
                        lifespan = ofMinutes(70)
                    ),
                    jiraFormula = StandaloneFormula.Builder(
                        database = it.database,
                        jiraHomeSource = it.jiraHomeSource,
                        productDistribution = PublicJiraSoftwareDistribution("7.13.0")
                    )
                        .computer(C5NineExtraLargeEphemeral())
                        .jiraVolume(Volume(300))
                        .databaseComputer(C5NineExtraLargeEphemeral())
                        .databaseVolume(Volume(300))
                        .build(),
                    virtualUsersFormula = AbsentVirtualUsersFormula(),
                    aws = aws
                )
            })
            .build()

        modification.modify()
    }
}
