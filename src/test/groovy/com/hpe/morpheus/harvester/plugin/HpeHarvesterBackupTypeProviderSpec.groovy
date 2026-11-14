// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.AbstractBackupTypeProvider
import com.morpheusdata.core.backup.BackupExecutionProvider
import com.morpheusdata.core.backup.BackupRestoreProvider
import com.morpheusdata.core.backup.BackupTypeProvider
import com.morpheusdata.model.BackupProvider as BackupProviderModel
import com.morpheusdata.model.OptionType
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Title
import spock.lang.Unroll

@Title('HpeHarvesterBackupTypeProvider Specification')
class HpeHarvesterBackupTypeProviderSpec extends Specification {

    @Subject
    HpeHarvesterBackupTypeProvider provider

    MorpheusContext morpheusContext
    Plugin plugin

    def setup() {
        morpheusContext = Mock(MorpheusContext)
        plugin = Mock(Plugin)
        provider = new HpeHarvesterBackupTypeProvider(plugin, morpheusContext)
    }

    def cleanup() {
        provider = null
        morpheusContext = null
        plugin = null
    }

    // Constructor Tests
    def "constructor should initialize with plugin and morpheusContext"() {
        when:
        def newProvider = new HpeHarvesterBackupTypeProvider(plugin, morpheusContext)

        then:
        newProvider.plugin == plugin
        newProvider.morpheus == morpheusContext
    }

    def "constructor should call super constructor"() {
        given:
        def newProvider = new HpeHarvesterBackupTypeProvider(plugin, morpheusContext)

        expect:
        newProvider instanceof AbstractBackupTypeProvider
    }

    // Interface Tests
    def "should implement BackupTypeProvider interface"() {
        expect:
        provider instanceof BackupTypeProvider
    }

    def "should extend AbstractBackupTypeProvider"() {
        expect:
        provider instanceof AbstractBackupTypeProvider
    }

    // getCode Tests
    def "getCode should return 'harvesterSnapshot'"() {
        expect:
        provider.getCode() == 'harvesterSnapshot'
    }

    def "getCode should return consistent value on multiple calls"() {
        when:
        def code1 = provider.getCode()
        def code2 = provider.getCode()
        def code3 = provider.getCode()

        then:
        code1 == code2
        code2 == code3
        code1 == 'harvesterSnapshot'
    }

    def "getCode should not return null"() {
        expect:
        provider.getCode() != null
    }

    def "getCode should not return empty string"() {
        expect:
        provider.getCode() != ''
    }

    // getName Tests
    def "getName should return 'Harvester Snapshot'"() {
        expect:
        provider.getName() == 'Harvester Snapshot'
    }

    def "getName should return consistent value on multiple calls"() {
        when:
        def name1 = provider.getName()
        def name2 = provider.getName()
        def name3 = provider.getName()

        then:
        name1 == name2
        name2 == name3
        name1 == 'Harvester Snapshot'
    }

    def "getName should not return null"() {
        expect:
        provider.getName() != null
    }

    def "getName should not return empty string"() {
        expect:
        provider.getName() != ''
    }

    // getContainerType Tests
    def "getContainerType should return 'single'"() {
        expect:
        provider.getContainerType() == 'single'
    }

    def "getContainerType should return consistent value"() {
        when:
        def type1 = provider.getContainerType()
        def type2 = provider.getContainerType()

        then:
        type1 == type2
        type1 == 'single'
    }

    def "getContainerType should not return null"() {
        expect:
        provider.getContainerType() != null
    }

    // getCopyToStore Tests
    def "getCopyToStore should return false"() {
        expect:
        provider.getCopyToStore() == false
    }

    def "getCopyToStore should return Boolean type"() {
        expect:
        provider.getCopyToStore() instanceof Boolean
    }

    def "getCopyToStore should return consistent value"() {
        when:
        def value1 = provider.getCopyToStore()
        def value2 = provider.getCopyToStore()

        then:
        value1 == value2
        value1 == false
    }

    // getDownloadEnabled Tests
    def "getDownloadEnabled should return false"() {
        expect:
        provider.getDownloadEnabled() == false
    }

    def "getDownloadEnabled should return Boolean type"() {
        expect:
        provider.getDownloadEnabled() instanceof Boolean
    }

    def "getDownloadEnabled should return consistent value"() {
        when:
        def value1 = provider.getDownloadEnabled()
        def value2 = provider.getDownloadEnabled()

        then:
        value1 == value2
        value1 == false
    }

    // getRestoreExistingEnabled Tests
    def "getRestoreExistingEnabled should return true"() {
        expect:
        provider.getRestoreExistingEnabled() == true
    }

    def "getRestoreExistingEnabled should return Boolean type"() {
        expect:
        provider.getRestoreExistingEnabled() instanceof Boolean
    }

    def "getRestoreExistingEnabled should return consistent value"() {
        when:
        def value1 = provider.getRestoreExistingEnabled()
        def value2 = provider.getRestoreExistingEnabled()

        then:
        value1 == value2
        value1 == true
    }

    // getRestoreNewEnabled Tests
    def "getRestoreNewEnabled should return true"() {
        expect:
        provider.getRestoreNewEnabled() == true
    }

    def "getRestoreNewEnabled should return Boolean type"() {
        expect:
        provider.getRestoreNewEnabled() instanceof Boolean
    }

    def "getRestoreNewEnabled should return consistent value"() {
        when:
        def value1 = provider.getRestoreNewEnabled()
        def value2 = provider.getRestoreNewEnabled()

        then:
        value1 == value2
        value1 == true
    }

    // getRestoreType Tests
    def "getRestoreType should return 'online'"() {
        expect:
        provider.getRestoreType() == 'online'
    }

    def "getRestoreType should return consistent value"() {
        when:
        def type1 = provider.getRestoreType()
        def type2 = provider.getRestoreType()

        then:
        type1 == type2
        type1 == 'online'
    }

    def "getRestoreType should not return null"() {
        expect:
        provider.getRestoreType() != null
    }

    @Unroll
    def "getRestoreType should be one of valid values: #validType"() {
        expect:
        def type = provider.getRestoreType()
        ['new', 'existing', 'online', 'offline', 'migration', 'failover'].contains(type)

        where:
        validType << ['new', 'existing', 'online', 'offline', 'migration', 'failover']
    }

    // getRestoreNewMode Tests
    def "getRestoreNewMode should return 'VM_RESTORE'"() {
        expect:
        provider.getRestoreNewMode() == 'VM_RESTORE'
    }

    def "getRestoreNewMode should return consistent value"() {
        when:
        def mode1 = provider.getRestoreNewMode()
        def mode2 = provider.getRestoreNewMode()

        then:
        mode1 == mode2
        mode1 == 'VM_RESTORE'
    }

    def "getRestoreNewMode should not return null"() {
        expect:
        provider.getRestoreNewMode() != null
    }

    @Unroll
    def "getRestoreNewMode should be one of valid modes: #validMode"() {
        expect:
        def mode = provider.getRestoreNewMode()
        ['DEFAULT', 'VM_RESTORE', 'TEMP_EXTRACT'].contains(mode)

        where:
        validMode << ['DEFAULT', 'VM_RESTORE', 'TEMP_EXTRACT']
    }

    // getHasCopyToStore Tests
    def "getHasCopyToStore should return false"() {
        expect:
        provider.getHasCopyToStore() == false
    }

    def "getHasCopyToStore should return Boolean type"() {
        expect:
        provider.getHasCopyToStore() instanceof Boolean
    }

    def "getHasCopyToStore should return consistent value"() {
        when:
        def value1 = provider.getHasCopyToStore()
        def value2 = provider.getHasCopyToStore()

        then:
        value1 == value2
        value1 == false
    }

    def "getHasCopyToStore should match getCopyToStore"() {
        expect:
        provider.getHasCopyToStore() == provider.getCopyToStore()
    }

    // getOptionTypes Tests
    def "getOptionTypes should return empty collection"() {
        when:
        def optionTypes = provider.getOptionTypes()

        then:
        optionTypes != null
        optionTypes instanceof Collection
        optionTypes.isEmpty()
    }

    def "getOptionTypes should return ArrayList"() {
        when:
        def optionTypes = provider.getOptionTypes()

        then:
        optionTypes instanceof ArrayList
    }

    def "getOptionTypes should return consistent empty collection"() {
        when:
        def types1 = provider.getOptionTypes()
        def types2 = provider.getOptionTypes()

        then:
        types1.isEmpty()
        types2.isEmpty()
    }

    def "getOptionTypes should return mutable collection"() {
        when:
        def optionTypes = provider.getOptionTypes()
        optionTypes.add(Mock(OptionType))

        then:
        optionTypes.size() == 1
    }

    // getExecutionProvider Tests
    def "getExecutionProvider should return HpeHarvesterBackupExecutionProvider"() {
        when:
        def executionProvider = provider.getExecutionProvider()

        then:
        executionProvider instanceof HpeHarvesterBackupExecutionProvider
        executionProvider instanceof BackupExecutionProvider
    }

    def "getExecutionProvider should cache provider instance"() {
        when:
        def provider1 = provider.getExecutionProvider()
        def provider2 = provider.getExecutionProvider()

        then:
        provider1.is(provider2)
    }

    def "getExecutionProvider should initialize executionProvider field"() {
        when:
        provider.executionProvider = null
        def executionProvider = provider.getExecutionProvider()

        then:
        executionProvider != null
        provider.executionProvider == executionProvider
    }

    def "getExecutionProvider should return existing instance if already initialized"() {
        given:
        def existingProvider = new HpeHarvesterBackupExecutionProvider(plugin, morpheusContext)
        provider.executionProvider = existingProvider

        when:
        def executionProvider = provider.getExecutionProvider()

        then:
        executionProvider.is(existingProvider)
    }

    def "getExecutionProvider should use plugin from constructor"() {
        when:
        def executionProvider = provider.getExecutionProvider()

        then:
        executionProvider.plugin == plugin
    }

    def "getExecutionProvider should use morpheusContext from constructor"() {
        when:
        def executionProvider = provider.getExecutionProvider()

        then:
        executionProvider.getMorpheus() == morpheusContext
    }

    // getRestoreProvider Tests
    def "getRestoreProvider should return HpeHarvesterBackupRestoreProvider"() {
        when:
        def restoreProvider = provider.getRestoreProvider()

        then:
        restoreProvider instanceof HpeHarvesterBackupRestoreProvider
        restoreProvider instanceof BackupRestoreProvider
    }

    def "getRestoreProvider should cache provider instance"() {
        when:
        def provider1 = provider.getRestoreProvider()
        def provider2 = provider.getRestoreProvider()

        then:
        provider1.is(provider2)
    }

    def "getRestoreProvider should initialize restoreProvider field"() {
        when:
        provider.restoreProvider = null
        def restoreProvider = provider.getRestoreProvider()

        then:
        restoreProvider != null
        provider.restoreProvider == restoreProvider
    }

    def "getRestoreProvider should return existing instance if already initialized"() {
        given:
        def existingProvider = new HpeHarvesterBackupRestoreProvider(plugin, morpheusContext)
        provider.restoreProvider = existingProvider

        when:
        def restoreProvider = provider.getRestoreProvider()

        then:
        restoreProvider.is(existingProvider)
    }

    def "getRestoreProvider should use plugin from constructor"() {
        when:
        def restoreProvider = provider.getRestoreProvider()

        then:
        restoreProvider.plugin == plugin
    }

    def "getRestoreProvider should use morpheusContext from constructor"() {
        when:
        def restoreProvider = provider.getRestoreProvider()

        then:
        restoreProvider.getMorpheus() == morpheusContext
    }

    // refresh Tests
    def "refresh should return success with valid parameters"() {
        given:
        def authConfig = [token: 'test-token', url: 'http://test']
        def backupProvider = Mock(BackupProviderModel)

        when:
        def response = provider.refresh(authConfig, backupProvider)

        then:
        response.success
        response instanceof ServiceResponse
    }

    def "refresh should handle null authConfig"() {
        given:
        def backupProvider = Mock(BackupProviderModel)

        when:
        def response = provider.refresh(null, backupProvider)

        then:
        response.success
    }

    def "refresh should handle null backupProvider"() {
        given:
        def authConfig = [token: 'test-token']

        when:
        def response = provider.refresh(authConfig, null)

        then:
        response.success
    }

    def "refresh should handle all null parameters"() {
        when:
        def response = provider.refresh(null, null)

        then:
        response.success
    }

    def "refresh should handle empty authConfig"() {
        given:
        def authConfig = [:]
        def backupProvider = Mock(BackupProviderModel)

        when:
        def response = provider.refresh(authConfig, backupProvider)

        then:
        response.success
    }

    @Unroll
    def "refresh should handle various authConfig values: #authValue"() {
        given:
        def authConfig = [key: authValue]
        def backupProvider = Mock(BackupProviderModel)

        when:
        def response = provider.refresh(authConfig, backupProvider)

        then:
        response.success

        where:
        authValue << ['string', 123, true, null, [:], []]
    }

    // clean Tests
    def "clean should return success with valid parameters"() {
        given:
        def backupProvider = Mock(BackupProviderModel)
        def opts = [option: 'value']

        when:
        def response = provider.clean(backupProvider, opts)

        then:
        response.success
        response instanceof ServiceResponse
    }

    def "clean should handle null backupProvider"() {
        given:
        def opts = [option: 'value']

        when:
        def response = provider.clean(null, opts)

        then:
        response.success
    }

    def "clean should handle null opts"() {
        given:
        def backupProvider = Mock(BackupProviderModel)

        when:
        def response = provider.clean(backupProvider, null)

        then:
        response.success
    }

    def "clean should handle all null parameters"() {
        when:
        def response = provider.clean(null, null)

        then:
        response.success
    }

    def "clean should handle empty opts"() {
        given:
        def backupProvider = Mock(BackupProviderModel)
        def opts = [:]

        when:
        def response = provider.clean(backupProvider, opts)

        then:
        response.success
    }

    @Unroll
    def "clean with different opts values: #optsValue"() {
        given:
        def backupProvider = Mock(BackupProviderModel)
        def opts = [key: optsValue]

        when:
        def response = provider.clean(backupProvider, opts)

        then:
        response.success

        where:
        optsValue << ['text', 999, false, null, [:]]
    }

    // Provider Lifecycle Tests
    def "should handle complete provider lifecycle"() {
        given:
        def authConfig = [token: 'test']
        def backupProvider = Mock(BackupProviderModel)
        def opts = [option: 'value']

        when: "get code and name"
        def code = provider.getCode()
        def name = provider.getName()

        then:
        code == 'harvesterSnapshot'
        name == 'Harvester Snapshot'

        when: "get configuration flags"
        def copyToStore = provider.getCopyToStore()
        def downloadEnabled = provider.getDownloadEnabled()
        def restoreExisting = provider.getRestoreExistingEnabled()
        def restoreNew = provider.getRestoreNewEnabled()

        then:
        !copyToStore
        !downloadEnabled
        restoreExisting
        restoreNew

        when: "get providers"
        def executionProvider = provider.getExecutionProvider()
        def restoreProvider = provider.getRestoreProvider()

        then:
        executionProvider instanceof HpeHarvesterBackupExecutionProvider
        restoreProvider instanceof HpeHarvesterBackupRestoreProvider

        when: "refresh and clean"
        def refreshResponse = provider.refresh(authConfig, backupProvider)
        def cleanResponse = provider.clean(backupProvider, opts)

        then:
        refreshResponse.success
        cleanResponse.success
    }

    // Thread Safety Tests
    def "should handle concurrent refresh calls"() {
        given:
        def backupProvider = Mock(BackupProviderModel)
        def results = Collections.synchronizedList([])

        when:
        def threads = (1..5).collect { index ->
            Thread.start {
                def response = provider.refresh([index: index], backupProvider)
                results.add(response.success)
            }
        }
        threads*.join()

        then:
        results.size() == 5
        results.every { it == true }
    }

    def "should handle concurrent clean calls"() {
        given:
        def backupProvider = Mock(BackupProviderModel)
        def results = Collections.synchronizedList([])

        when:
        def threads = (1..5).collect { index ->
            Thread.start {
                def response = provider.clean(backupProvider, [index: index])
                results.add(response.success)
            }
        }
        threads*.join()

        then:
        results.size() == 5
        results.every { it == true }
    }

    // Boolean Flag Consistency Tests
    def "boolean flags should have expected values"() {
        expect:
        provider.getCopyToStore() == false
        provider.getDownloadEnabled() == false
        provider.getRestoreExistingEnabled() == true
        provider.getRestoreNewEnabled() == true
        provider.getHasCopyToStore() == false
    }

    def "restore capabilities should be enabled"() {
        expect:
        provider.getRestoreExistingEnabled()
        provider.getRestoreNewEnabled()
        provider.getRestoreType() == 'online'
        provider.getRestoreNewMode() == 'VM_RESTORE'
    }

    // Idempotency Tests
    def "getCode should be idempotent"() {
        when:
        def codes = (1..10).collect { provider.getCode() }

        then:
        codes.unique().size() == 1
        codes.every { it == 'harvesterSnapshot' }
    }

    def "getName should be idempotent"() {
        when:
        def names = (1..10).collect { provider.getName() }

        then:
        names.unique().size() == 1
        names.every { it == 'Harvester Snapshot' }
    }

    def "refresh should be idempotent"() {
        given:
        def authConfig = [token: 'test']
        def backupProvider = Mock(BackupProviderModel)

        when:
        def response1 = provider.refresh(authConfig, backupProvider)
        def response2 = provider.refresh(authConfig, backupProvider)
        def response3 = provider.refresh(authConfig, backupProvider)

        then:
        response1.success
        response2.success
        response3.success
    }

    // Provider Instance Tests
    def "multiple provider instances should be independent"() {
        given:
        def provider1 = new HpeHarvesterBackupTypeProvider(plugin, morpheusContext)
        def provider2 = new HpeHarvesterBackupTypeProvider(Mock(Plugin), Mock(MorpheusContext))

        expect:
        !provider1.is(provider2)
        provider1.getCode() == provider2.getCode()
        provider1.getName() == provider2.getName()
    }

    def "provider should maintain state consistency"() {
        when:
        provider.getExecutionProvider()
        provider.getRestoreProvider()
        provider.getOptionTypes()

        then:
        provider.plugin == plugin
        provider.morpheus == morpheusContext
        provider.executionProvider != null
        provider.restoreProvider != null
    }
}
