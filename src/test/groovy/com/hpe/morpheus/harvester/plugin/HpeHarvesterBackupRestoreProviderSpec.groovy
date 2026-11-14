// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.BackupRestoreProvider
import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupRestore
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.Instance
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Title
import spock.lang.Unroll

@Title('HpeHarvesterBackupRestoreProvider Specification')
class HpeHarvesterBackupRestoreProviderSpec extends Specification {

    @Subject
    HpeHarvesterBackupRestoreProvider provider

    MorpheusContext morpheusContext
    Plugin plugin

    def setup() {
        morpheusContext = Mock(MorpheusContext)
        plugin = Mock(Plugin)
        provider = new HpeHarvesterBackupRestoreProvider(plugin, morpheusContext)
    }

    def cleanup() {
        provider = null
        morpheusContext = null
        plugin = null
    }

    // Constructor Tests
    def "constructor should initialize with plugin and morpheusContext"() {
        when:
        def newProvider = new HpeHarvesterBackupRestoreProvider(plugin, morpheusContext)

        then:
        newProvider.plugin == plugin
        newProvider.morpheusContext == morpheusContext
    }

    def "constructor should accept null plugin"() {
        when:
        def newProvider = new HpeHarvesterBackupRestoreProvider(null, morpheusContext)

        then:
        newProvider.plugin == null
        newProvider.morpheusContext == morpheusContext
    }

    def "constructor should accept null morpheusContext"() {
        when:
        def newProvider = new HpeHarvesterBackupRestoreProvider(plugin, null)

        then:
        newProvider.plugin == plugin
        newProvider.morpheusContext == null
    }

    // getMorpheus Tests
    def "getMorpheus should return morpheusContext"() {
        expect:
        provider.getMorpheus() == morpheusContext
    }

    def "getMorpheus should handle null morpheusContext"() {
        given:
        provider.morpheusContext = null

        expect:
        provider.getMorpheus() == null
    }

    def "getMorpheus should return same instance on multiple calls"() {
        when:
        def context1 = provider.getMorpheus()
        def context2 = provider.getMorpheus()

        then:
        context1.is(context2)
    }

    // BackupRestoreProvider Interface Tests
    def "should implement BackupRestoreProvider interface"() {
        expect:
        provider instanceof BackupRestoreProvider
    }

    // configureRestoreBackup Tests
    def "configureRestoreBackup should return success with valid parameters"() {
        given:
        def backupResult = Mock(BackupResult)
        def config = [option: 'value']
        def opts = [param: 'test']

        when:
        def response = provider.configureRestoreBackup(backupResult, config, opts)

        then:
        response.success
        response instanceof ServiceResponse
    }

    def "configureRestoreBackup should handle null backupResult"() {
        given:
        def config = [option: 'value']
        def opts = [param: 'test']

        when:
        def response = provider.configureRestoreBackup(null, config, opts)

        then:
        response.success
    }

    def "configureRestoreBackup should handle null config"() {
        given:
        def backupResult = Mock(BackupResult)
        def opts = [param: 'test']

        when:
        def response = provider.configureRestoreBackup(backupResult, null, opts)

        then:
        response.success
    }

    def "configureRestoreBackup should handle null opts"() {
        given:
        def backupResult = Mock(BackupResult)
        def config = [option: 'value']

        when:
        def response = provider.configureRestoreBackup(backupResult, config, null)

        then:
        response.success
    }

    def "configureRestoreBackup should handle all null parameters"() {
        when:
        def response = provider.configureRestoreBackup(null, null, null)

        then:
        response.success
    }

    def "configureRestoreBackup should handle empty config map"() {
        given:
        def backupResult = Mock(BackupResult)
        def config = [:]
        def opts = [param: 'test']

        when:
        def response = provider.configureRestoreBackup(backupResult, config, opts)

        then:
        response.success
    }

    def "configureRestoreBackup should handle empty opts map"() {
        given:
        def backupResult = Mock(BackupResult)
        def config = [option: 'value']
        def opts = [:]

        when:
        def response = provider.configureRestoreBackup(backupResult, config, opts)

        then:
        response.success
    }

    @Unroll
    def "configureRestoreBackup should handle various config values: #configValue"() {
        given:
        def backupResult = Mock(BackupResult)
        def config = [key: configValue]
        def opts = [:]

        when:
        def response = provider.configureRestoreBackup(backupResult, config, opts)

        then:
        response.success

        where:
        configValue << ['string', 123, true, false, null, [], [:]]
    }

    // getBackupRestoreInstanceConfig Tests
    def "getBackupRestoreInstanceConfig should return success with valid parameters"() {
        given:
        def backupResult = Mock(BackupResult)
        def instance = Mock(Instance)
        def restoreConfig = [setting: 'value']
        def opts = [param: 'test']

        when:
        def response = provider.getBackupRestoreInstanceConfig(backupResult, instance, restoreConfig, opts)

        then:
        response.success
        response instanceof ServiceResponse
    }

    def "getBackupRestoreInstanceConfig should handle null backupResult"() {
        given:
        def instance = Mock(Instance)
        def restoreConfig = [setting: 'value']
        def opts = [param: 'test']

        when:
        def response = provider.getBackupRestoreInstanceConfig(null, instance, restoreConfig, opts)

        then:
        response.success
    }

    def "getBackupRestoreInstanceConfig should handle null instance"() {
        given:
        def backupResult = Mock(BackupResult)
        def restoreConfig = [setting: 'value']
        def opts = [param: 'test']

        when:
        def response = provider.getBackupRestoreInstanceConfig(backupResult, null, restoreConfig, opts)

        then:
        response.success
    }

    def "getBackupRestoreInstanceConfig should handle null restoreConfig"() {
        given:
        def backupResult = Mock(BackupResult)
        def instance = Mock(Instance)
        def opts = [param: 'test']

        when:
        def response = provider.getBackupRestoreInstanceConfig(backupResult, instance, null, opts)

        then:
        response.success
    }

    def "getBackupRestoreInstanceConfig should handle null opts"() {
        given:
        def backupResult = Mock(BackupResult)
        def instance = Mock(Instance)
        def restoreConfig = [setting: 'value']

        when:
        def response = provider.getBackupRestoreInstanceConfig(backupResult, instance, restoreConfig, null)

        then:
        response.success
    }

    def "getBackupRestoreInstanceConfig should handle all null parameters"() {
        when:
        def response = provider.getBackupRestoreInstanceConfig(null, null, null, null)

        then:
        response.success
    }

    @Unroll
    def "getBackupRestoreInstanceConfig should handle various restoreConfig values: #configValue"() {
        given:
        def backupResult = Mock(BackupResult)
        def instance = Mock(Instance)
        def restoreConfig = [key: configValue]
        def opts = [:]

        when:
        def response = provider.getBackupRestoreInstanceConfig(backupResult, instance, restoreConfig, opts)

        then:
        response.success

        where:
        configValue << ['text', 456, true, null, [:], []]
    }

    // validateRestoreBackup Tests
    def "validateRestoreBackup should return success with valid parameters"() {
        given:
        def backupResult = Mock(BackupResult)
        def opts = [param: 'value']

        when:
        def response = provider.validateRestoreBackup(backupResult, opts)

        then:
        response.success
        response instanceof ServiceResponse
    }

    def "validateRestoreBackup should handle null backupResult"() {
        given:
        def opts = [param: 'value']

        when:
        def response = provider.validateRestoreBackup(null, opts)

        then:
        response.success
    }

    def "validateRestoreBackup should handle null opts"() {
        given:
        def backupResult = Mock(BackupResult)

        when:
        def response = provider.validateRestoreBackup(backupResult, null)

        then:
        response.success
    }

    def "validateRestoreBackup should handle all null parameters"() {
        when:
        def response = provider.validateRestoreBackup(null, null)

        then:
        response.success
    }

    def "validateRestoreBackup should handle empty opts map"() {
        given:
        def backupResult = Mock(BackupResult)
        def opts = [:]

        when:
        def response = provider.validateRestoreBackup(backupResult, opts)

        then:
        response.success
    }

    // getRestoreOptions Tests
    def "getRestoreOptions should return success with valid parameters"() {
        given:
        def backup = Mock(Backup)
        def opts = [option: 'value']

        when:
        def response = provider.getRestoreOptions(backup, opts)

        then:
        response.success
        response instanceof ServiceResponse
    }

    def "getRestoreOptions should handle null backup"() {
        given:
        def opts = [option: 'value']

        when:
        def response = provider.getRestoreOptions(null, opts)

        then:
        response.success
    }

    def "getRestoreOptions should handle null opts"() {
        given:
        def backup = Mock(Backup)

        when:
        def response = provider.getRestoreOptions(backup, null)

        then:
        response.success
    }

    def "getRestoreOptions should handle all null parameters"() {
        when:
        def response = provider.getRestoreOptions(null, null)

        then:
        response.success
    }

    def "getRestoreOptions should handle empty opts map"() {
        given:
        def backup = Mock(Backup)
        def opts = [:]

        when:
        def response = provider.getRestoreOptions(backup, opts)

        then:
        response.success
    }

    @Unroll
    def "getRestoreOptions should handle various opts values: #optsValue"() {
        given:
        def backup = Mock(Backup)
        def opts = [key: optsValue]

        when:
        def response = provider.getRestoreOptions(backup, opts)

        then:
        response.success

        where:
        optsValue << ['string', 789, false, null, [:]]
    }

    // restoreBackup Tests
    def "restoreBackup should return success with valid parameters"() {
        given:
        def backupRestore = Mock(BackupRestore)
        def backupResult = Mock(BackupResult)
        def backup = Mock(Backup)
        def opts = [param: 'value']

        when:
        def response = provider.restoreBackup(backupRestore, backupResult, backup, opts)

        then:
        response.success
        response instanceof ServiceResponse
    }

    def "restoreBackup should handle null backupRestore"() {
        given:
        def backupResult = Mock(BackupResult)
        def backup = Mock(Backup)
        def opts = [param: 'value']

        when:
        def response = provider.restoreBackup(null, backupResult, backup, opts)

        then:
        response.success
    }

    def "restoreBackup should handle null backupResult"() {
        given:
        def backupRestore = Mock(BackupRestore)
        def backup = Mock(Backup)
        def opts = [param: 'value']

        when:
        def response = provider.restoreBackup(backupRestore, null, backup, opts)

        then:
        response.success
    }

    def "restoreBackup should handle null backup"() {
        given:
        def backupRestore = Mock(BackupRestore)
        def backupResult = Mock(BackupResult)
        def opts = [param: 'value']

        when:
        def response = provider.restoreBackup(backupRestore, backupResult, null, opts)

        then:
        response.success
    }

    def "restoreBackup should handle null opts"() {
        given:
        def backupRestore = Mock(BackupRestore)
        def backupResult = Mock(BackupResult)
        def backup = Mock(Backup)

        when:
        def response = provider.restoreBackup(backupRestore, backupResult, backup, null)

        then:
        response.success
    }

    def "restoreBackup should handle all null parameters"() {
        when:
        def response = provider.restoreBackup(null, null, null, null)

        then:
        response.success
    }

    def "restoreBackup should handle empty opts map"() {
        given:
        def backupRestore = Mock(BackupRestore)
        def backupResult = Mock(BackupResult)
        def backup = Mock(Backup)
        def opts = [:]

        when:
        def response = provider.restoreBackup(backupRestore, backupResult, backup, opts)

        then:
        response.success
    }

    @Unroll
    def "restoreBackup with different backupRestore states: #backupRestoreId"() {
        given:
        def backupRestore = Mock(BackupRestore) {
            getId() >> backupRestoreId
        }
        def backupResult = Mock(BackupResult)
        def backup = Mock(Backup)
        def opts = [:]

        when:
        def response = provider.restoreBackup(backupRestore, backupResult, backup, opts)

        then:
        response.success

        where:
        backupRestoreId << [1L, 100L, 999L, null]
    }

    // refreshBackupRestoreResult Tests
    def "refreshBackupRestoreResult should return success with valid parameters"() {
        given:
        def backupRestore = Mock(BackupRestore)
        def backupResult = Mock(BackupResult)

        when:
        def response = provider.refreshBackupRestoreResult(backupRestore, backupResult)

        then:
        response.success
        response instanceof ServiceResponse
    }

    def "refreshBackupRestoreResult should handle null backupRestore"() {
        given:
        def backupResult = Mock(BackupResult)

        when:
        def response = provider.refreshBackupRestoreResult(null, backupResult)

        then:
        response.success
    }

    def "refreshBackupRestoreResult should handle null backupResult"() {
        given:
        def backupRestore = Mock(BackupRestore)

        when:
        def response = provider.refreshBackupRestoreResult(backupRestore, null)

        then:
        response.success
    }

    def "refreshBackupRestoreResult should handle all null parameters"() {
        when:
        def response = provider.refreshBackupRestoreResult(null, null)

        then:
        response.success
    }

    @Unroll
    def "refreshBackupRestoreResult with different restore IDs: #restoreId"() {
        given:
        def backupRestore = Mock(BackupRestore) {
            getId() >> restoreId
        }
        def backupResult = Mock(BackupResult)

        when:
        def response = provider.refreshBackupRestoreResult(backupRestore, backupResult)

        then:
        response.success

        where:
        restoreId << [1L, 50L, 999L, null]
    }

    // Backup Restore Lifecycle Tests
    def "should handle complete restore lifecycle"() {
        given:
        def backupRestore = Mock(BackupRestore)
        def backupResult = Mock(BackupResult)
        def backup = Mock(Backup)
        def instance = Mock(Instance)
        def config = [restore: 'config']
        def restoreConfig = [instance: 'config']
        def opts = [option: 'value']

        when: "configure restore"
        def configResponse = provider.configureRestoreBackup(backupResult, config, opts)

        then:
        configResponse.success

        when: "get instance config"
        def instanceConfigResponse = provider.getBackupRestoreInstanceConfig(backupResult, instance, restoreConfig, opts)

        then:
        instanceConfigResponse.success

        when: "validate restore"
        def validateResponse = provider.validateRestoreBackup(backupResult, opts)

        then:
        validateResponse.success

        when: "get restore options"
        def optionsResponse = provider.getRestoreOptions(backup, opts)

        then:
        optionsResponse.success

        when: "execute restore"
        def restoreResponse = provider.restoreBackup(backupRestore, backupResult, backup, opts)

        then:
        restoreResponse.success

        when: "refresh restore"
        def refreshResponse = provider.refreshBackupRestoreResult(backupRestore, backupResult)

        then:
        refreshResponse.success
    }

    // Thread Safety Tests
    def "should handle concurrent configureRestoreBackup calls"() {
        given:
        def backupResult = Mock(BackupResult)
        def results = Collections.synchronizedList([])

        when:
        def threads = (1..5).collect { index ->
            Thread.start {
                def response = provider.configureRestoreBackup(backupResult, [index: index], [:])
                results.add(response.success)
            }
        }
        threads*.join()

        then:
        results.size() == 5
        results.every { it == true }
    }

    def "should handle concurrent restoreBackup calls"() {
        given:
        def results = Collections.synchronizedList([])

        when:
        def threads = (1..5).collect { index ->
            Thread.start {
                def backupRestore = Mock(BackupRestore)
                def backupResult = Mock(BackupResult)
                def backup = Mock(Backup)
                def response = provider.restoreBackup(backupRestore, backupResult, backup, [index: index])
                results.add(response.success)
            }
        }
        threads*.join()

        then:
        results.size() == 5
        results.every { it == true }
    }

    def "should handle concurrent refreshBackupRestoreResult calls"() {
        given:
        def results = Collections.synchronizedList([])

        when:
        def threads = (1..5).collect { index ->
            Thread.start {
                def backupRestore = Mock(BackupRestore)
                def backupResult = Mock(BackupResult)
                def response = provider.refreshBackupRestoreResult(backupRestore, backupResult)
                results.add(response.success)
            }
        }
        threads*.join()

        then:
        results.size() == 5
        results.every { it == true }
    }

    // Idempotency Tests
    def "configureRestoreBackup should be idempotent"() {
        given:
        def backupResult = Mock(BackupResult)
        def config = [option: 'value']
        def opts = [param: 'test']

        when:
        def response1 = provider.configureRestoreBackup(backupResult, config, opts)
        def response2 = provider.configureRestoreBackup(backupResult, config, opts)
        def response3 = provider.configureRestoreBackup(backupResult, config, opts)

        then:
        response1.success
        response2.success
        response3.success
    }

    def "validateRestoreBackup should be idempotent"() {
        given:
        def backupResult = Mock(BackupResult)
        def opts = [param: 'value']

        when:
        def response1 = provider.validateRestoreBackup(backupResult, opts)
        def response2 = provider.validateRestoreBackup(backupResult, opts)
        def response3 = provider.validateRestoreBackup(backupResult, opts)

        then:
        response1.success
        response2.success
        response3.success
    }

    def "getRestoreOptions should be idempotent"() {
        given:
        def backup = Mock(Backup)
        def opts = [option: 'value']

        when:
        def response1 = provider.getRestoreOptions(backup, opts)
        def response2 = provider.getRestoreOptions(backup, opts)
        def response3 = provider.getRestoreOptions(backup, opts)

        then:
        response1.success
        response2.success
        response3.success
    }

    // Provider Instance Tests
    def "provider should maintain consistent state across method calls"() {
        given:
        def backupResult = Mock(BackupResult)
        def backup = Mock(Backup)

        when:
        provider.configureRestoreBackup(backupResult, [:], [:])
        provider.validateRestoreBackup(backupResult, [:])
        provider.getRestoreOptions(backup, [:])

        then:
        provider.morpheusContext == morpheusContext
        provider.plugin == plugin
    }

    def "multiple provider instances should be independent"() {
        given:
        def provider1 = new HpeHarvesterBackupRestoreProvider(plugin, morpheusContext)
        def provider2 = new HpeHarvesterBackupRestoreProvider(Mock(Plugin), Mock(MorpheusContext))

        expect:
        !provider1.is(provider2)
        provider1.plugin != provider2.plugin
        provider1.morpheusContext != provider2.morpheusContext
    }
}
