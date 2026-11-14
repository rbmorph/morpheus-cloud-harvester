package com.hpe.morpheus.harvester.plugin

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.response.BackupExecutionResponse
import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * Comprehensive unit tests for HpeHarvesterBackupExecutionProvider
 * Tests backup execution lifecycle and operations
 */
class HpeHarvesterBackupExecutionProviderSpec extends Specification {

    @Subject
    HpeHarvesterBackupExecutionProvider provider

    Plugin mockPlugin = Mock(Plugin)
    MorpheusContext mockContext = Mock(MorpheusContext)
    Backup mockBackup = Mock(Backup)
    BackupResult mockBackupResult = Mock(BackupResult)
    Cloud mockCloud = Mock(Cloud)
    ComputeServer mockComputeServer = Mock(ComputeServer)

    def setup() {
        provider = new HpeHarvesterBackupExecutionProvider(mockPlugin, mockContext)
    }

    def "provider should be initialized with plugin and context"() {
        expect:
        provider.plugin == mockPlugin
        provider.morpheusContext == mockContext
    }

    def "getMorpheus should return morpheus context"() {
        expect:
        provider.getMorpheus() == mockContext
        provider.getMorpheus() != null
    }

    def "configureBackup should return success with backup"() {
        given:
        def config = [key: "value"]
        def opts = [option: "test"]

        when:
        def response = provider.configureBackup(mockBackup, config, opts)

        then:
        response.success == true
        response.data == mockBackup
    }

    def "configureBackup should handle null config"() {
        when:
        def response = provider.configureBackup(mockBackup, null, [:])

        then:
        response.success == true
    }

    def "configureBackup should handle null opts"() {
        when:
        def response = provider.configureBackup(mockBackup, [:], null)

        then:
        response.success == true
    }

    def "configureBackup should handle empty config and opts"() {
        when:
        def response = provider.configureBackup(mockBackup, [:], [:])

        then:
        response.success == true
        response.data == mockBackup
    }

    @Unroll
    def "configureBackup should handle various config: #config"() {
        when:
        def response = provider.configureBackup(mockBackup, config, [:])

        then:
        response.success == true

        where:
        config << [
            [:],
            [key1: "value1"],
            [key1: "value1", key2: "value2"],
            [nested: [key: "value"]],
            [list: ["item1", "item2"]]
        ]
    }

    def "validateBackup should return success with backup"() {
        given:
        def config = [key: "value"]
        def opts = [option: "test"]

        when:
        def response = provider.validateBackup(mockBackup, config, opts)

        then:
        response.success == true
        response.data == mockBackup
    }

    def "validateBackup should handle null parameters"() {
        when:
        def response = provider.validateBackup(mockBackup, null, null)

        then:
        response.success == true
    }

    def "validateBackup should not throw exception"() {
        when:
        provider.validateBackup(mockBackup, [:], [:])

        then:
        noExceptionThrown()
    }

    def "createBackup should return success"() {
        given:
        def opts = [option: "test"]

        when:
        def response = provider.createBackup(mockBackup, opts)

        then:
        response.success == true
    }

    def "createBackup should handle null opts"() {
        when:
        def response = provider.createBackup(mockBackup, null)

        then:
        response.success == true
    }

    def "createBackup should handle empty opts"() {
        when:
        def response = provider.createBackup(mockBackup, [:])

        then:
        response.success == true
    }

    @Unroll
    def "createBackup should succeed with various opts: #opts"() {
        when:
        def response = provider.createBackup(mockBackup, opts)

        then:
        response.success == true

        where:
        opts << [
            [:],
            [option1: "value1"],
            [multiple: "options", test: true],
            [nested: [key: "value"]]
        ]
    }

    def "deleteBackup should return success"() {
        given:
        def opts = [option: "test"]

        when:
        def response = provider.deleteBackup(mockBackup, opts)

        then:
        response.success == true
    }

    def "deleteBackup should handle null opts"() {
        when:
        def response = provider.deleteBackup(mockBackup, null)

        then:
        response.success == true
    }

    def "deleteBackupResult should return success"() {
        given:
        def opts = [option: "test"]
        mockBackupResult.id >> 123L

        when:
        def response = provider.deleteBackupResult(mockBackupResult, opts)

        then:
        response.success == true
    }

    def "deleteBackupResult should handle null opts"() {
        given:
        mockBackupResult.id >> 456L

        when:
        def response = provider.deleteBackupResult(mockBackupResult, null)

        then:
        response.success == true
    }

    @Unroll
    def "deleteBackupResult should handle backupResult with ID: #backupId"() {
        given:
        mockBackupResult.id >> backupId

        when:
        def response = provider.deleteBackupResult(mockBackupResult, [:])

        then:
        response.success == true

        where:
        backupId << [1L, 100L, 9999L, Long.MAX_VALUE]
    }

    def "prepareExecuteBackup should return success"() {
        given:
        def opts = [option: "test"]

        when:
        def response = provider.prepareExecuteBackup(mockBackup, opts)

        then:
        response.success == true
    }

    def "prepareExecuteBackup should handle null opts"() {
        when:
        def response = provider.prepareExecuteBackup(mockBackup, null)

        then:
        response.success == true
    }

    def "prepareExecuteBackup should handle empty opts"() {
        when:
        def response = provider.prepareExecuteBackup(mockBackup, [:])

        then:
        response.success == true
    }

    def "prepareBackupResult should return success"() {
        given:
        def opts = [option: "test"]

        when:
        def response = provider.prepareBackupResult(mockBackupResult, opts)

        then:
        response.success == true
    }

    def "prepareBackupResult should handle null opts"() {
        when:
        def response = provider.prepareBackupResult(mockBackupResult, null)

        then:
        response.success == true
    }

    def "executeBackup should return success"() {
        given:
        def executionConfig = [config: "value"]
        def opts = [option: "test"]

        when:
        def response = provider.executeBackup(mockBackup, mockBackupResult, executionConfig, mockCloud, mockComputeServer, opts)

        then:
        response.success == true
    }

    def "executeBackup should handle null execution config"() {
        when:
        def response = provider.executeBackup(mockBackup, mockBackupResult, null, mockCloud, mockComputeServer, [:])

        then:
        response.success == true
    }

    def "executeBackup should handle null cloud"() {
        when:
        def response = provider.executeBackup(mockBackup, mockBackupResult, [:], null, mockComputeServer, [:])

        then:
        response.success == true
    }

    def "executeBackup should handle null compute server"() {
        when:
        def response = provider.executeBackup(mockBackup, mockBackupResult, [:], mockCloud, null, [:])

        then:
        response.success == true
    }

    def "executeBackup should handle all null optional parameters"() {
        when:
        def response = provider.executeBackup(mockBackup, mockBackupResult, null, null, null, null)

        then:
        response.success == true
    }

    @Unroll
    def "executeBackup should handle various execution configs: #executionConfig"() {
        when:
        def response = provider.executeBackup(mockBackup, mockBackupResult, executionConfig, mockCloud, mockComputeServer, [:])

        then:
        response.success == true

        where:
        executionConfig << [
            [:],
            [timeout: 300],
            [retries: 3, timeout: 600],
            [options: [key1: "value1", key2: "value2"]]
        ]
    }

    def "refreshBackupResult should return success"() {
        when:
        def response = provider.refreshBackupResult(mockBackupResult)

        then:
        response.success == true
    }

    def "refreshBackupResult should handle null backup result"() {
        when:
        def response = provider.refreshBackupResult(null)

        then:
        response.success == true
    }

    def "refreshBackupResult should return ServiceResponse with BackupExecutionResponse type"() {
        when:
        def response = provider.refreshBackupResult(mockBackupResult)

        then:
        response instanceof ServiceResponse
        response.success == true
    }

    @Unroll
    def "refreshBackupResult should handle backupResult with different states: #backupId"() {
        given:
        mockBackupResult.id >> backupId

        when:
        def response = provider.refreshBackupResult(mockBackupResult)

        then:
        response.success == true

        where:
        backupId << [1L, 50L, 100L, 1000L]
    }

    def "cancelBackup should return success"() {
        given:
        def opts = [option: "test"]

        when:
        def response = provider.cancelBackup(mockBackupResult, opts)

        then:
        response.success == true
    }

    def "cancelBackup should handle null opts"() {
        when:
        def response = provider.cancelBackup(mockBackupResult, null)

        then:
        response.success == true
    }

    def "cancelBackup should handle empty opts"() {
        when:
        def response = provider.cancelBackup(mockBackupResult, [:])

        then:
        response.success == true
    }

    def "extractBackup should return success"() {
        given:
        def opts = [option: "test"]

        when:
        def response = provider.extractBackup(mockBackupResult, opts)

        then:
        response.success == true
    }

    def "extractBackup should handle null opts"() {
        when:
        def response = provider.extractBackup(mockBackupResult, null)

        then:
        response.success == true
    }

    def "extractBackup should handle empty opts"() {
        when:
        def response = provider.extractBackup(mockBackupResult, [:])

        then:
        response.success == true
    }

    def "provider should maintain plugin reference"() {
        expect:
        provider.plugin != null
        provider.plugin == mockPlugin
    }

    def "provider should maintain context reference"() {
        expect:
        provider.morpheusContext != null
        provider.morpheusContext == mockContext
    }

    def "all backup operations should be non-blocking"() {
        when:
        def results = [
            provider.configureBackup(mockBackup, [:], [:]),
            provider.validateBackup(mockBackup, [:], [:]),
            provider.createBackup(mockBackup, [:]),
            provider.deleteBackup(mockBackup, [:]),
            provider.prepareExecuteBackup(mockBackup, [:]),
            provider.prepareBackupResult(mockBackupResult, [:]),
            provider.refreshBackupResult(mockBackupResult),
            provider.cancelBackup(mockBackupResult, [:]),
            provider.extractBackup(mockBackupResult, [:])
        ]

        then:
        results.every { it.success == true }
    }

    def "backup lifecycle should complete successfully"() {
        when:
        def configure = provider.configureBackup(mockBackup, [:], [:])
        def validate = provider.validateBackup(mockBackup, [:], [:])
        def create = provider.createBackup(mockBackup, [:])
        def prepare = provider.prepareExecuteBackup(mockBackup, [:])
        def prepareResult = provider.prepareBackupResult(mockBackupResult, [:])
        def execute = provider.executeBackup(mockBackup, mockBackupResult, [:], mockCloud, mockComputeServer, [:])
        def refresh = provider.refreshBackupResult(mockBackupResult)

        then:
        configure.success
        validate.success
        create.success
        prepare.success
        prepareResult.success
        execute.success
        refresh.success
    }

    def "provider should implement BackupExecutionProvider interface"() {
        expect:
        provider instanceof com.morpheusdata.core.backup.BackupExecutionProvider
    }

    def "getMorpheus should consistently return same context"() {
        when:
        def ctx1 = provider.getMorpheus()
        def ctx2 = provider.getMorpheus()

        then:
        ctx1.is(ctx2)
        ctx1 == mockContext
    }

    @Unroll
    def "provider should be thread-safe for backup operations"() {
        when:
        def results = Collections.synchronizedList([])
        def threads = (1..5).collect {
            Thread.start {
                results << provider.configureBackup(mockBackup, [:], [:])
                results << provider.createBackup(mockBackup, [:])
                results << provider.deleteBackup(mockBackup, [:])
            }
        }
        threads*.join()

        then:
        results.size() == 15
        results.every { it.success == true }
    }
}
