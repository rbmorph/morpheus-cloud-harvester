package com.hpe.morpheus.harvester.plugin

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.BackupJobProvider
import com.morpheusdata.model.BackupProvider as BackupProviderModel
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OptionType
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * Comprehensive unit tests for HpeHarvesterBackupProvider
 * Tests backup provider configuration and capabilities
 */
class HpeHarvesterBackupProviderSpec extends Specification {

    @Subject
    HpeHarvesterBackupProvider provider

    Plugin mockPlugin = Mock(Plugin)
    MorpheusContext mockContext = Mock(MorpheusContext)
    BackupProviderModel mockBackupProvider = Mock(BackupProviderModel)

    def setup() {
        provider = new HpeHarvesterBackupProvider(mockPlugin, mockContext)
    }

    def "provider should have correct code"() {
        expect:
        provider.getCode() == 'harvesterBackup'
    }

    def "provider code should not be null or empty"() {
        expect:
        provider.getCode() != null
        provider.getCode() != ""
        !provider.getCode().isEmpty()
    }

    def "provider should have correct name"() {
        expect:
        provider.getName() == 'Harvester Snapshot'
    }

    def "provider name should not be null or empty"() {
        expect:
        provider.getName() != null
        provider.getName() != ""
    }

    def "provider name should contain Harvester"() {
        expect:
        provider.getName().contains("Harvester")
        provider.getName().contains("Snapshot")
    }

    def "provider should return icon"() {
        when:
        def icon = provider.getIcon()

        then:
        icon != null
        icon instanceof Icon
        icon.path == "harvester.svg"
        icon.darkPath == "harvester.svg"
    }

    def "provider should be enabled"() {
        expect:
        provider.getEnabled() == true
    }

    def "provider should be creatable"() {
        expect:
        provider.getCreatable() == true
    }

    def "provider should support restore to new workload"() {
        expect:
        provider.getRestoreNewEnabled() == true
    }

    def "provider should have backups capability"() {
        expect:
        provider.getHasBackups() == true
    }

    def "provider should support creating jobs"() {
        expect:
        provider.getHasCreateJob() == true
    }

    def "provider should support cloning jobs"() {
        expect:
        provider.getHasCloneJob() == true
    }

    def "provider should support adding to job"() {
        expect:
        provider.getHasAddToJob() == true
    }

    def "provider should support optional job"() {
        expect:
        provider.getHasOptionalJob() == true
    }

    def "provider should support schedules"() {
        expect:
        provider.getHasSchedule() == true
    }

    def "provider should support jobs"() {
        expect:
        provider.getHasJobs() == true
    }

    def "provider should support retention count"() {
        expect:
        provider.getHasRetentionCount() == true
    }

    @Unroll
    def "provider capability #capability should return true"() {
        expect:
        method.call(provider) == true

        where:
        capability                  | method
        "enabled"                   | { it.getEnabled() }
        "creatable"                 | { it.getCreatable() }
        "restoreNewEnabled"         | { it.getRestoreNewEnabled() }
        "hasBackups"                | { it.getHasBackups() }
        "hasCreateJob"              | { it.getHasCreateJob() }
        "hasCloneJob"               | { it.getHasCloneJob() }
        "hasAddToJob"               | { it.getHasAddToJob() }
        "hasOptionalJob"            | { it.getHasOptionalJob() }
        "hasSchedule"               | { it.getHasSchedule() }
        "hasJobs"                   | { it.getHasJobs() }
        "hasRetentionCount"         | { it.getHasRetentionCount() }
    }

    def "getOptionTypes should return empty collection"() {
        when:
        def optionTypes = provider.getOptionTypes()

        then:
        optionTypes != null
        optionTypes instanceof Collection
        optionTypes.isEmpty()
    }

    def "getReplicationGroupOptionTypes should return empty collection"() {
        when:
        def optionTypes = provider.getReplicationGroupOptionTypes()

        then:
        optionTypes != null
        optionTypes instanceof Collection
        optionTypes.isEmpty()
    }

    def "getReplicationOptionTypes should return empty collection"() {
        when:
        def optionTypes = provider.getReplicationOptionTypes()

        then:
        optionTypes != null
        optionTypes instanceof Collection
        optionTypes.isEmpty()
    }

    def "getBackupJobOptionTypes should return empty collection"() {
        when:
        def optionTypes = provider.getBackupJobOptionTypes()

        then:
        optionTypes != null
        optionTypes instanceof Collection
        optionTypes.isEmpty()
    }

    def "getBackupOptionTypes should return empty collection"() {
        when:
        def optionTypes = provider.getBackupOptionTypes()

        then:
        optionTypes != null
        optionTypes instanceof Collection
        optionTypes.isEmpty()
    }

    def "getInstanceReplicationGroupOptionTypes should return empty collection"() {
        when:
        def optionTypes = provider.getInstanceReplicationGroupOptionTypes()

        then:
        optionTypes != null
        optionTypes instanceof Collection
        optionTypes.isEmpty()
    }

    @Unroll
    def "option type collection #collectionName should be empty"() {
        when:
        def result = method.call(provider)

        then:
        result != null
        result.isEmpty()

        where:
        collectionName                          | method
        "optionTypes"                           | { it.getOptionTypes() }
        "replicationGroupOptionTypes"           | { it.getReplicationGroupOptionTypes() }
        "replicationOptionTypes"                | { it.getReplicationOptionTypes() }
        "backupJobOptionTypes"                  | { it.getBackupJobOptionTypes() }
        "backupOptionTypes"                     | { it.getBackupOptionTypes() }
        "instanceReplicationGroupOptionTypes"   | { it.getInstanceReplicationGroupOptionTypes() }
    }

    def "getBackupJobProvider should return BackupJobProvider"() {
        when:
        def jobProvider = provider.getBackupJobProvider()

        then:
        jobProvider != null
        jobProvider instanceof BackupJobProvider
    }

    def "getBackupJobProvider should cache instance"() {
        when:
        def provider1 = provider.getBackupJobProvider()
        def provider2 = provider.getBackupJobProvider()

        then:
        provider1.is(provider2)
    }

    def "configureBackupProvider should return success"() {
        given:
        def config = [key: "value"]
        def opts = [option: "test"]

        when:
        def response = provider.configureBackupProvider(mockBackupProvider, config, opts)

        then:
        response.success == true
        response.data == mockBackupProvider
    }

    def "configureBackupProvider should handle null config"() {
        when:
        def response = provider.configureBackupProvider(mockBackupProvider, null, [:])

        then:
        response.success == true
    }

    def "configureBackupProvider should handle null opts"() {
        when:
        def response = provider.configureBackupProvider(mockBackupProvider, [:], null)

        then:
        response.success == true
    }

    @Unroll
    def "configureBackupProvider should handle various configs: #config"() {
        when:
        def response = provider.configureBackupProvider(mockBackupProvider, config, [:])

        then:
        response.success == true

        where:
        config << [
            [:],
            [url: "https://test.com"],
            [username: "admin", password: "secret"],
            [timeout: 300, retries: 3],
            [nested: [key1: "value1", key2: "value2"]]
        ]
    }

    def "validateBackupProvider should return success"() {
        given:
        def opts = [option: "test"]

        when:
        def response = provider.validateBackupProvider(mockBackupProvider, opts)

        then:
        response.success == true
        response.data == mockBackupProvider
    }

    def "validateBackupProvider should handle null opts"() {
        when:
        def response = provider.validateBackupProvider(mockBackupProvider, null)

        then:
        response.success == true
    }

    def "validateBackupProvider should handle empty opts"() {
        when:
        def response = provider.validateBackupProvider(mockBackupProvider, [:])

        then:
        response.success == true
    }

    def "deleteBackupProvider should return success"() {
        given:
        def opts = [option: "test"]

        when:
        def response = provider.deleteBackupProvider(mockBackupProvider, opts)

        then:
        response.success == true
    }

    def "deleteBackupProvider should handle null opts"() {
        when:
        def response = provider.deleteBackupProvider(mockBackupProvider, null)

        then:
        response.success == true
    }

    def "deleteBackupProvider should handle empty opts"() {
        when:
        def response = provider.deleteBackupProvider(mockBackupProvider, [:])

        then:
        response.success == true
    }

    def "refresh should return success"() {
        when:
        def response = provider.refresh(mockBackupProvider)

        then:
        response.success == true
    }

    def "refresh should handle null backup provider"() {
        when:
        def response = provider.refresh(null)

        then:
        response.success == true
    }

    def "refresh should not throw exception"() {
        when:
        provider.refresh(mockBackupProvider)

        then:
        noExceptionThrown()
    }

    def "provider should extend AbstractBackupProvider"() {
        expect:
        provider instanceof com.morpheusdata.core.backup.AbstractBackupProvider
    }

    def "provider should maintain morpheus context reference"() {
        expect:
        provider.morpheus == mockContext
        provider.morpheus != null
    }

    def "provider should maintain plugin reference"() {
        expect:
        provider.plugin == mockPlugin
        provider.plugin != null
    }

    def "provider code should be suitable as identifier"() {
        expect:
        provider.getCode() ==~ /^[a-zA-Z]+$/
        !provider.getCode().contains(" ")
        !provider.getCode().contains("-")
    }

    def "provider name should be human-readable"() {
        expect:
        provider.getName().contains(" ")
        provider.getName().length() > 5
    }

    def "icon paths should be valid"() {
        when:
        def icon = provider.getIcon()

        then:
        icon.path.endsWith(".svg")
        icon.darkPath.endsWith(".svg")
    }

    def "all capabilities should be boolean"() {
        expect:
        provider.getEnabled() instanceof Boolean
        provider.getCreatable() instanceof Boolean
        provider.getRestoreNewEnabled() instanceof Boolean
        provider.getHasBackups() instanceof Boolean
        provider.getHasCreateJob() instanceof Boolean
        provider.getHasCloneJob() instanceof Boolean
        provider.getHasAddToJob() instanceof Boolean
        provider.getHasOptionalJob() instanceof Boolean
        provider.getHasSchedule() instanceof Boolean
        provider.getHasJobs() instanceof Boolean
        provider.getHasRetentionCount() instanceof Boolean
    }

    def "provider lifecycle operations should succeed"() {
        when:
        def configure = provider.configureBackupProvider(mockBackupProvider, [:], [:])
        def validate = provider.validateBackupProvider(mockBackupProvider, [:])
        def refresh = provider.refresh(mockBackupProvider)
        def delete = provider.deleteBackupProvider(mockBackupProvider, [:])

        then:
        configure.success
        validate.success
        refresh.success
        delete.success
    }

    def "provider should handle concurrent operations"() {
        when:
        def results = Collections.synchronizedList([])
        def threads = (1..5).collect {
            Thread.start {
                results << provider.configureBackupProvider(mockBackupProvider, [:], [:])
                results << provider.validateBackupProvider(mockBackupProvider, [:])
                results << provider.refresh(mockBackupProvider)
            }
        }
        threads*.join()

        then:
        results.size() == 15
        results.every { it.success == true }
    }

    def "getBackupJobProvider should initialize lazily"() {
        when:
        def provider1 = provider.getBackupJobProvider()

        then:
        provider1 != null
        provider.backupJobProvider != null
    }

    def "provider should support multiple refresh calls"() {
        when:
        def results = (1..10).collect {
            provider.refresh(mockBackupProvider)
        }

        then:
        results.every { it.success == true }
    }

    @Unroll
    def "provider capability #capability should be consistent across calls"() {
        when:
        def result1 = method.call(provider)
        def result2 = method.call(provider)

        then:
        result1 == result2
        result1 == true

        where:
        capability          | method
        "enabled"           | { it.getEnabled() }
        "creatable"         | { it.getCreatable() }
        "hasBackups"        | { it.getHasBackups() }
        "hasSchedule"       | { it.getHasSchedule() }
    }
}
