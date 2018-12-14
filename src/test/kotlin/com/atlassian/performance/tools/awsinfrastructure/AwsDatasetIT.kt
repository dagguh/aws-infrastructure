package com.atlassian.performance.tools.awsinfrastructure

import com.amazonaws.regions.Regions
import com.atlassian.performance.tools.aws.api.StorageLocation
import com.atlassian.performance.tools.awsinfrastructure.IntegrationTestRuntime.aws
import com.atlassian.performance.tools.awsinfrastructure.IntegrationTestRuntime.taskWorkspace
import com.atlassian.performance.tools.awsinfrastructure.api.DatasetCatalogue
import com.atlassian.performance.tools.awsinfrastructure.jira.LicenseOverridingDatabase
import com.atlassian.performance.tools.awsinfrastructure.jira.SshMysqlClient
import com.atlassian.performance.tools.infrastructure.api.database.Database
import com.atlassian.performance.tools.infrastructure.api.dataset.Dataset
import com.atlassian.performance.tools.ssh.api.Ssh
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.net.URI
import java.time.Duration.ofMinutes
import java.util.*

class AwsDatasetIT {

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

    /**
     * [Official timebomb licenses](https://developer.atlassian.com/platform/marketplace/timebomb-licenses-for-testing-server-apps/)
     */
    @Test
    fun shouldOverrideTimebombLicense() {
        val sourceDataset = DatasetCatalogue().custom(
            label = "700 issues",
            location = StorageLocation(
                uri = URI("s3://jpt-custom-datasets-storage-a008820-datasetbucket-1sjxdtrv5hdhj/")
                    .resolve("dataset-919767fe-55b5-4c06-a3f4-c1d8222b6a2d"),
                region = Regions.EU_WEST_1
            )
        )
        // 10 user starter non-eval host product license, expires in 3 hours
        val timebombLicense = """
            AAABiQ0ODAoPeNp1kk9TwjAQxe/9FJnxXKYpeoCZHqCtgsqfgaIO4yWELURD0tm0KN/eWOjYdvD68
            vbtb3dzM9GKTBgS2iOU9n3a7/pkHiXE96jvbNhho3XnWXBQBuKtyIVWQTxN4sV8MV7GTirMHk5QO
            ZJTBsG91eITvPdJBEeQOgN0uNRHwIYtLKWGa1ocNoCzdGUATUA9h2uVdhjPxRGCHAtw5gXyPTMQs
            RwCn1Lf9XzXv3NqwVN2gGCZDBYWstLj70zgqSyad0fVWPXgJaClGUfB8KGXuG+rl1v3ab0euUOPv
            jofAlmD/XG8GJBY5YAZCtMa9Ze5MagVZAGKX/FVE4eyMDZtqrdgAq+19zJlWEr/Na0TXjkTx4KLj
            WzeKbyIjaAJE7aDYpa2tTSO+mvbCrBKo/ryate4Up9KfylnhjumhGEl0SCXzBjB1B9Q/QYhQulrH
            /fcue6svl1di8BwFFnZKAGTE3mGIalGksliJxTZVqTmvLF6fXxksjhzpkwaqP5s3fMDBMYhRDAtA
            hUAhcR3uL05YCxbclq7h1dNa+Nc+j4CFBrdN005oVlMN9yBlWeM4TlnrOhqX02j3
            """.trimIndent()
        // 10 user Jira Software Data Center license, expires in 3 hours
        val timebombDcLicense = """
            AAAB8w0ODAoPeNp9Uk2P2jAQvedXWOoNydmELVKLFKlL4u7SLglKQj+27cEkA3gb7GjssMu/rwnQl
            s9DDvHMvPfmvXmTN0BGfE08n3jdftfv927J/SgnXc9/58wRQC5UXQO6j6IAqYGVwgglAxbnLB2nw
            4w5cbOcAiaziQbUge85oZKGFybmSwjKmiMKvfjATcW1Fly6hVo64waLBdcQcQPBhot6Per5zo4lX
            9fQjofJaMTScHj3uC+x11rgup0b3z7sudiIi+oSWQa4AhxGweD+fU6/Tb68pZ+fnh7owPO/Os8Cu
            VujKpvCuJsfqtXMvHAE1+KKFQQGG3A+2cp412XJeQjSHLVkzVQXKOrWn/bljH/nNmslXPa30+nES
            U4/Jikdp0k0CfNhEtNJxmwhCBGsFSWZrolZANmhECYLVQISu9gzFIb8WBhT/+zf3MyVe2DOTbWdo
            LCd+OWSSBGpDCmFNiimjQGLLDQxihSNNmppU3Yd67c0ILksjhOxqsKU3eUsooPvG4kXUrli/MlF7
            dayEU7kb6lepJOxOLAf7XneFmkfCuCp95nh+LdwhfegL8E5l0LzNo4IVlApi0Vy0GZvs9O6b+vHZ
            xzBv0toB3Yuk5lCwuualHs8fSD0/3NqdZ48nBd+5bjYilfNdokZr6zmP7TmY5YwLAIUNq8MbmR8G
            faV9ulfLz1K+3g9j1YCFDeq7aYROMQbwMIvHimNt7/bJCCIX02nj
            """.trimIndent()
        val licenseOverriddenDataset = sourceDataset.overrideDatabase(
            LicenseOverridingDatabase(
                database = sourceDataset.database,
                licenses = listOf(
                    timebombLicense,
                    timebombDcLicense
                )
            )
        )
        lateinit var postProvisioningLicenses: String

        AwsDataset(licenseOverriddenDataset).modify(
            aws = aws,
            workspace = workspace,
            newDatasetName = UUID.randomUUID().toString()
        ) { infrastructure ->
            val database = infrastructure.jira.database ?: throw Exception("The database should have been provisioned")
            Ssh(database.host).newConnection().use { ssh ->
                val result = SshMysqlClient().runSql(ssh, "SELECT * FROM jiradb.productlicense;")
                postProvisioningLicenses = result.output
            }
        }

        assertThat(postProvisioningLicenses.flattenSshMultiline())
            .contains(timebombLicense.flattenMultiline())
            .contains(timebombDcLicense.flattenMultiline())
    }
}

private fun String.flattenSshMultiline(): String = replace("\\n", "")
private fun String.flattenMultiline(): String = replace("\n", "")

private fun Dataset.overrideDatabase(
    database: Database
): Dataset = Dataset(
    database = database,
    jiraHomeSource = jiraHomeSource,
    label = label
)
