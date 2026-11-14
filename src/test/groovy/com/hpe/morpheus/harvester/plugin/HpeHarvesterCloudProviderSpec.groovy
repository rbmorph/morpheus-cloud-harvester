// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.hpe.morpheus.harvester.plugin.sync.HarvesterInventorySync
import com.hpe.morpheus.harvester.plugin.utils.DBUtils
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupTypeService
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerGroupType
import com.morpheusdata.model.OptionType
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Unroll

class HpeHarvesterCloudProviderSpec extends Specification {

    HpeHarvesterPlugin plugin = Mock(HpeHarvesterPlugin)
    MorpheusContext morpheusContext = Mock(MorpheusContext)
    MorpheusServices services = Mock(MorpheusServices)
    MorpheusSynchronousComputeServerGroupService clusterService = Mock(MorpheusSynchronousComputeServerGroupService)
    MorpheusSynchronousComputeServerGroupTypeService clusterTypeService = Mock(MorpheusSynchronousComputeServerGroupTypeService)
    HpeHarvesterCloudProvider provider

    def setup() {
        morpheusContext.getServices() >> services
        services.getCluster() >> clusterService
        clusterService.getType() >> clusterTypeService
        provider = new HpeHarvesterCloudProvider(plugin, morpheusContext)
    }

    private static Cloud cloud(Map config, Map props = [:]) {
        Cloud c = new Cloud()
        c.id = 7L
        c.name = 'harv'
        c.serviceUrl = 'https://10.0.0.10'
        props.each { k, v -> c."$k" = v }
        c.setConfigMap(config)
        // no stored credential lookup in unit tests
        c.accountCredentialLoaded = true
        return c
    }

    def "identity and provision type code"() {
        expect:
        provider.code == HarvesterConstants.CLOUD_PROVIDER_CODE
        // the cloud keeps its code; the provision type now has the provisioning provider's code
        provider.code == HarvesterConstants.LEGACY_PROVISION_TYPE_CODE
        HarvesterConstants.PROVISION_TYPE_CODE == HarvesterConstants.PROVISION_PROVIDER_CODE
        provider.name == 'Harvester'
        provider.description
        provider.icon.path == 'harvester-logo.svg'
        provider.circularIcon.path == 'harvester-circular.svg'
        provider.morpheus.is(morpheusContext)
        provider.plugin.is(plugin)
        provider.defaultProvisionTypeCode == HarvesterConstants.PROVISION_TYPE_CODE
    }

    def "option types cover URL, API key, credentials and inventory"() {
        when:
        def options = provider.optionTypes

        then:
        options*.fieldName.containsAll(['serviceUrl', 'apiKey', 'type', 'serviceUsername', 'servicePassword', 'importExisting'])
        options.find { it.fieldName == 'serviceUrl' }.required
        options.find { it.fieldName == 'apiKey' }.inputType == OptionType.InputType.PASSWORD
        options.find { it.fieldName == 'type' }.inputType == OptionType.InputType.CREDENTIAL
        options*.code.unique().size() == options.size()
    }

    def "capabilities"() {
        expect:
        provider.hasComputeZonePools()
        provider.provisionRequiresResourcePool()
        provider.hasNetworks()
        provider.hasDatastores()
        provider.hasCloudInit()
        !provider.hasFolders()
        !provider.hasBareMetal()
        !provider.supportsDistributedWorker()
        provider.computeServerTypes.isEmpty()
        provider.availableBackupProviders.isEmpty()
    }

    def "provision providers come from the plugin"() {
        given:
        def pp = Mock(HpeHarvesterProvisionProvider) {
            getCode() >> 'harvester-provision-provider-plugin'
            getProvisionTypeCode() >> 'harvester'
        }
        plugin.getProvidersByType(_) >> [pp]

        expect:
        provider.availableProvisionProviders == [pp]
        provider.getProvisionProvider('harvester-provision-provider-plugin').is(pp)
        // looked up by the provision type code, which is also the cloud code
        provider.getProvisionProvider('harvester').is(pp)
        provider.getProvisionProvider('other') == null
    }

    def "resolveCredentials reads the API key and local username and password"() {
        when:
        def creds = provider.resolveCredentials(cloud([apiKey: ' token-a:b ', username: 'admin', password: 'pw']))

        then:
        creds.apiUrl == 'https://10.0.0.10'
        creds.apiKey == 'token-a:b'
        creds.username == 'admin'
        creds.password == 'pw'
    }

    def "resolveCredentials reads the username and password typed on the cloud form"() {
        when:
        def creds = provider.resolveCredentials(cloud([:], [serviceUsername: ' admin ', servicePassword: 'pw']))

        then:
        creds.apiKey == null
        creds.username == 'admin'
        creds.password == 'pw'
    }

    def "resolveCredentials uses a stored credential from the validate request"() {
        given:
        def request = new ValidateCloudRequest('cred-user', 'cred-pw', 'username-password', [:])

        when:
        def creds = provider.resolveCredentials(cloud([username: 'local-user', password: 'local-pw']), request)

        then:
        creds.username == 'cred-user'
        creds.password == 'cred-pw'
        creds.apiKey == null
    }

    def "resolveCredentials falls back to local fields for a local credential"() {
        given:
        def request = new ValidateCloudRequest(null, null, 'local', [:])

        expect:
        provider.resolveCredentials(cloud([username: 'local-user', password: 'local-pw']), request).username == 'local-user'
    }

    def "buildConnection prefers the API key over a login"() {
        when:
        def withKey = HpeHarvesterCloudProvider.buildConnection([apiUrl: 'https://h', apiKey: 'token-a:b', username: 'u', password: 'p'])
        def withLogin = HpeHarvesterCloudProvider.buildConnection([apiUrl: 'https://h', apiKey: null, username: 'u', password: 'p'])

        then:
        withKey.serviceToken == 'token-a:b'
        withKey.serviceUsername == null
        withLogin.serviceToken == null
        withLogin.serviceUsername == 'u'
        withLogin.servicePassword == 'p'
        withLogin.serviceUrl == 'https://h'
    }

    def "applySettings copies credentials and flags, and reports changes"() {
        given:
        def connection = new ComputeServerGroup()
        def c = cloud([:])

        when:
        def first = HpeHarvesterCloudProvider.applySettings(connection, c, [apiUrl: 'https://h', apiKey: 'token-a:b'], true)
        def second = HpeHarvesterCloudProvider.applySettings(connection, c, [apiUrl: 'https://h', apiKey: 'token-a:b'], true)

        then:
        first
        !second
        connection.serviceUrl == 'https://h'
        connection.serviceToken == 'token-a:b'
        connection.name == 'harv'
        HpeHarvesterClusterProvider.isCloudManaged(connection)
        connection.getConfigProperty('importExisting') == 'on'
    }

    @Unroll
    def "isImportExisting(#value) == #expected"() {
        expect:
        HpeHarvesterCloudProvider.isImportExisting(cloud(value == 'unset' ? [:] : [importExisting: value])) == expected

        where:
        value   || expected
        'unset' || false
        'on'    || true
        true    || true
        'off'   || false
        false   || false
    }

    def "checkConnection succeeds against Harvester"() {
        given:
        def api = Mock(HarvesterApiClient)
        api.get(HarvesterConstants.HARVESTER_API_V1_NAMESPACES, [limit: '1']) >> ServiceResponse.success([kind: 'NamespaceList'])
        api.get(HarvesterConstants.HARVESTER_SETTING_SERVER_VERSION) >> ServiceResponse.success([value: 'v1.6.0'])

        expect:
        HpeHarvesterCloudProvider.checkConnection(api) == null
    }

    @Unroll
    def "checkConnection explains a #code"() {
        given:
        def api = Mock(HarvesterApiClient)
        api.getBaseUrl() >> 'https://h/k8s/clusters/local'
        api.get(HarvesterConstants.HARVESTER_API_V1_NAMESPACES, [limit: '1']) >> new ServiceResponse(success: false, errorCode: code, msg: 'x')

        expect:
        HpeHarvesterCloudProvider.checkConnection(api).contains(text)

        where:
        code  || text
        '401' || 'rejected the credentials'
        '403' || 'permissions'
        '502' || 'Unable to reach'
    }

    def "checkConnection spots a cluster that is not Harvester"() {
        given:
        def api = Mock(HarvesterApiClient)
        api.getBaseUrl() >> 'https://k8s:6443'
        api.get(HarvesterConstants.HARVESTER_API_V1_NAMESPACES, [limit: '1']) >> ServiceResponse.success([:])
        api.get(HarvesterConstants.HARVESTER_SETTING_SERVER_VERSION) >> new ServiceResponse(success: false, errorCode: '404')

        expect:
        HpeHarvesterCloudProvider.checkConnection(api).contains('not a Harvester cluster')
    }

    def "checkConnection reports an authentication exception"() {
        given:
        def api = Mock(HarvesterApiClient)
        api.get(_, _) >> { throw new RuntimeException('Failed to authenticate: no creds') }

        expect:
        HpeHarvesterCloudProvider.checkConnection(api).contains('no creds')
    }

    def "validate requires a URL and credentials"() {
        when:
        def noCreds = provider.validate(cloud([:]), new ValidateCloudRequest(null, null, 'local', [:]))
        def noUrl = provider.validate(cloud([apiKey: 'token-a:b'], [serviceUrl: null]), new ValidateCloudRequest(null, null, 'local', [:]))

        then:
        !noCreds.success
        noCreds.errors.apiKey
        !noUrl.success
        noUrl.errors.serviceUrl
    }

    def "validate fails without a cloud"() {
        expect:
        !provider.validate(null, null).success
    }

    def "validate checks the connection"() {
        given:
        GroovySpy(HpeHarvesterCloudProvider, global: true, constructorArgs: [plugin, morpheusContext])
        HpeHarvesterCloudProvider.checkConnection(_) >> result

        when:
        def response = provider.validate(cloud([apiKey: 'token-a:b']), new ValidateCloudRequest(null, null, 'local', [:]))

        then:
        response.success == ok

        where:
        result        || ok
        null          || true
        'bad creds'   || false
    }

    def "ensureConnection creates the connection record and its resource pool on first use"() {
        given:
        def groupType = new ComputeServerGroupType(code: HarvesterConstants.CLUSTER_CODE)
        GroovySpy(DBUtils, global: true)
        ComputeServerGroup created = null

        when:
        def connection = provider.ensureConnection(cloud([apiKey: 'token-a:b']))

        then:
        1 * clusterService.find(_) >> null
        1 * clusterTypeService.find(_) >> groupType
        1 * clusterService.create(_) >> { args -> created = args[0]; created.id = 55L; created }
        1 * DBUtils.createDefaultResourcePool(morpheusContext, _) >> null
        connection.id == 55L
        created.type.is(groupType)
        created.category == 'harvester.cloud.7'
        created.uuid ==~ /[0-9a-f-]{36}/
        created.serviceToken == 'token-a:b'
        created.serviceUrl == 'https://10.0.0.10'
        HpeHarvesterClusterProvider.isCloudManaged(created)
    }

    def "ensureConnection updates an existing record only when something changed"() {
        given:
        def existing = new ComputeServerGroup(id: 55L, name: 'harv', serviceUrl: 'https://10.0.0.10', serviceToken: 'token-a:b')
        existing.setConfigProperty(HarvesterConstants.CONFIG_MANAGED_BY_CLOUD, true)
        existing.setConfigProperty('importExisting', 'on')

        when:
        provider.ensureConnection(cloud([apiKey: newKey, importExisting: 'on']))

        then:
        1 * clusterService.find(_) >> existing
        saves * clusterService.save(existing) >> existing
        0 * clusterService.create(_)

        where:
        newKey      || saves
        'token-a:b' || 0
        'token-c:d' || 1
    }

    def "refresh marks the cloud offline when Harvester can't be reached"() {
        given:
        def existing = new ComputeServerGroup(id: 55L, name: 'harv', serviceUrl: 'https://10.0.0.10', serviceToken: 'token-a:b')
        clusterService.find(_) >> existing
        clusterService.save(_) >> existing
        GroovySpy(HpeHarvesterCloudProvider, global: true, constructorArgs: [plugin, morpheusContext])
        HpeHarvesterCloudProvider.checkConnection(_) >> 'Harvester rejected the credentials.'

        when:
        def response = provider.refresh(cloud([apiKey: 'token-a:b']))

        then:
        !response.success
        response.data.status == Cloud.Status.offline
    }

    def "refresh runs the inventory sync"() {
        given:
        def existing = new ComputeServerGroup(id: 55L, name: 'harv', serviceUrl: 'https://10.0.0.10', serviceToken: 'token-a:b')
        clusterService.find(_) >> existing
        clusterService.save(_) >> existing
        GroovySpy(HpeHarvesterCloudProvider, global: true, constructorArgs: [plugin, morpheusContext])
        HpeHarvesterCloudProvider.checkConnection(_) >> null
        HpeHarvesterCloudProvider.isClusterHealthOn(_) >> false
        GroovySpy(HarvesterInventorySync, global: true, constructorArgs: [null, null, false])
        def sync = Mock(HarvesterInventorySync, constructorArgs: [null, null, false])

        when:
        def response = provider.refresh(cloud([apiKey: 'token-a:b', importExisting: 'off']))

        then:
        1 * new HarvesterInventorySync(plugin, existing, false) >> sync
        // the global spy and the mock both record execute(), so only check that it ran
        (1.._) * sync.execute()
        response.success
    }

    def "deleteCloud removes the connection record"() {
        given:
        def existing = new ComputeServerGroup(id: 55L, name: 'harv')
        GroovySpy(HpeHarvesterClusterProvider, global: true, constructorArgs: [plugin, morpheusContext])
        def clusterProvider = Mock(HpeHarvesterClusterProvider, constructorArgs: [plugin, morpheusContext])

        when:
        def response = provider.deleteCloud(cloud([:]))

        then:
        1 * clusterService.find(_) >> existing
        1 * new HpeHarvesterClusterProvider(plugin, morpheusContext) >> clusterProvider
        1 * clusterProvider.deleteCluster(existing) >> ServiceResponse.success()
        1 * clusterService.remove(existing) >> true
        response.success
    }

    def "deleteCloud succeeds when there is no connection record"() {
        when:
        def response = provider.deleteCloud(cloud([:]))

        then:
        1 * clusterService.find(_) >> null
        0 * clusterService.remove(_)
        response.success
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Resource pools = namespaces
    // ---------------------------------------------------------------------------------------------------------------

    private static ComputeServerGroup connection() {
        return new ComputeServerGroup(id: 55L, name: 'harv')
    }

    private static CloudPool namespacePool(String name) {
        def pool = new CloudPool(name: name, type: HarvesterConstants.NAMESPACE_TYPE, externalId: "uid-${name}".toString(),
                category: "${HarvesterConstants.CATEGORY_NAMESPACE_CLUSTER_PREFIX}55".toString())
        pool.serverGroup = connection()
        return pool
    }

    def "pools can be created and deleted"() {
        expect:
        provider.canCreateCloudPools()
        provider.canDeleteCloudPools()
    }

    @Unroll
    def "createCloudPool rejects the name '#name'"() {
        when:
        def response = provider.createCloudPool(cloud([:]), new CloudPool(name: name))

        then:
        !response.success
        0 * clusterService.find(_)

        where:
        name << [null, '', 'Has-Caps', 'under_score', '-lead', 'trail-', 'a' * 64, 'kube-system', 'harvester-system']
    }

    def "createCloudPool creates the namespace and fills in the pool like the sync does"() {
        given:
        GroovySpy(HarvesterApiClient, global: true, constructorArgs: [null])
        def api = Mock(HarvesterApiClient)
        new HarvesterApiClient(_) >> api
        def pool = new CloudPool(name: 'team-a')
        def c = cloud([:])

        when:
        def response = provider.createCloudPool(c, pool)

        then:
        1 * clusterService.find(_) >> connection()
        1 * api.post(HarvesterConstants.HARVESTER_API_V1_NAMESPACES, { it.metadata.name == 'team-a' && it.kind == 'Namespace' }) >>
                ServiceResponse.success([metadata: [name: 'team-a', uid: 'uid-123']])
        response.success
        response.data.is(pool)
        pool.externalId == 'uid-123'
        pool.type == HarvesterConstants.NAMESPACE_TYPE
        pool.category == "${HarvesterConstants.CATEGORY_NAMESPACE_CLUSTER_PREFIX}55".toString()
        pool.serverGroup == null
        pool.cloud.is(c)
        pool.active
        pool.removable
    }

    def "createCloudPool reports a namespace that already exists"() {
        given:
        GroovySpy(HarvesterApiClient, global: true, constructorArgs: [null])
        def api = Mock(HarvesterApiClient)
        new HarvesterApiClient(_) >> api
        clusterService.find(_) >> connection()
        api.post(_, _) >> new ServiceResponse(success: false, errorCode: '409')

        when:
        def response = provider.createCloudPool(cloud([:]), new CloudPool(name: 'team-a'))

        then:
        !response.success
        response.msg.contains('already exists')
    }

    def "createCloudPool needs a synced cloud"() {
        given:
        clusterService.find(_) >> null

        expect:
        !provider.createCloudPool(cloud([:]), new CloudPool(name: 'team-a')).success
    }

    @Unroll
    def "removeCloudPool refuses to remove #desc"() {
        when:
        def response = provider.removeCloudPool(cloud([:]), pool)

        then:
        !response.success
        0 * clusterService.find(_)

        where:
        desc                      | pool
        'the default namespace'   | namespacePool('default')
        'a system namespace'      | namespacePool('longhorn-system')
    }

    @Unroll
    def "removeCloudPool removes #desc without touching Harvester"() {
        given:
        GroovySpy(HarvesterApiClient, global: true, constructorArgs: [null])

        when:
        def response = provider.removeCloudPool(cloud([:]), pool)

        then:
        response.success
        0 * new HarvesterApiClient(_)

        where:
        desc                               | pool
        'a pool that is not a namespace'   | new CloudPool(name: 'harv', type: HarvesterConstants.RESOURCE_POOL_TYPE_DEFAULT)
        'a namespace pool with no link'    | new CloudPool(name: 'test55', type: HarvesterConstants.NAMESPACE_TYPE)
    }

    def "removeCloudPool refuses a namespace that still has VMs or volumes"() {
        given:
        GroovySpy(HarvesterApiClient, global: true, constructorArgs: [null])
        def api = Mock(HarvesterApiClient)
        new HarvesterApiClient(_) >> api
        api.get({ it.contains('virtualmachines') && !it.contains('images') }, _) >> ServiceResponse.success([items: [[metadata: [name: 'vm1']]]])
        api.get({ it.contains('persistentvolumeclaims') }, _) >> ServiceResponse.success([items: [[metadata: [name: 'disk']]]])
        api.get({ it.contains('virtualmachineimages') }, _) >> ServiceResponse.success([items: []])

        when:
        def response = provider.removeCloudPool(cloud([:]), namespacePool('team-a'))

        then:
        !response.success
        response.msg.contains('virtual machines')
        response.msg.contains('volumes')
        0 * api.delete(_)
    }

    def "removeCloudPool deletes an empty namespace"() {
        given:
        GroovySpy(HarvesterApiClient, global: true, constructorArgs: [null])
        def api = Mock(HarvesterApiClient)
        new HarvesterApiClient(_) >> api
        api.get(_, _) >> ServiceResponse.success([items: []])

        when:
        def response = provider.removeCloudPool(cloud([:]), namespacePool('team-a'))

        then:
        1 * api.delete('/api/v1/namespaces/team-a') >> deleteResult
        response.success == ok

        where:
        deleteResult                                          || ok
        ServiceResponse.success([:])                          || true
        new ServiceResponse(success: false, errorCode: '404') || true
        new ServiceResponse(success: false, errorCode: '403') || false
    }

    def "removeCloudPool stops when Harvester can't be asked what the namespace holds"() {
        given:
        GroovySpy(HarvesterApiClient, global: true, constructorArgs: [null])
        def api = Mock(HarvesterApiClient)
        new HarvesterApiClient(_) >> api
        api.get(_, _) >> new ServiceResponse(success: false, errorCode: '500', msg: 'boom')

        when:
        def response = provider.removeCloudPool(cloud([:]), namespacePool('team-a'))

        then:
        !response.success
        0 * api.delete(_)
    }

    def "updateCloudPool refuses to rename a namespace"() {
        given:
        def poolService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService)
        def cloudService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService)
        services.getCloud() >> cloudService
        cloudService.getPool() >> poolService
        def stored = namespacePool('team-a')
        stored.id = 9L
        poolService.get(9L) >> stored
        def renamed = namespacePool('team-b')
        renamed.id = 9L
        def same = namespacePool('team-a')
        same.id = 9L

        expect:
        !provider.updateCloudPool(cloud([:]), renamed).success
        provider.updateCloudPool(cloud([:]), same).success
    }

    def "hideClusterPool hides the cluster-wide pool once"() {
        given:
        def poolService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService)
        def cloudService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService)
        services.getCloud() >> cloudService
        cloudService.getPool() >> poolService
        def clusterPool = new CloudPool(name: 'harv', type: HarvesterConstants.RESOURCE_POOL_TYPE_DEFAULT)

        when:
        provider.hideClusterPool(connection())
        provider.hideClusterPool(connection())

        then:
        2 * poolService.find(_) >> clusterPool
        1 * poolService.save(clusterPool) >> clusterPool
        clusterPool.hidden
        !clusterPool.removable
        !clusterPool.active
    }

    def "setUpCustomHarvesterPlan turns every custom option on once"() {
        given:
        def planService = Mock((Class) Class.forName('com.morpheusdata.core.synchronous.MorpheusSynchronousServicePlanService'))
        services.getServicePlan() >> planService
        def plan = new com.morpheusdata.model.ServicePlan(code: 'custom-harvester', name: 'Custom')
        plan.customCores = true
        plan.customCpu = false
        plan.maxCpu = 4L

        when:
        provider.setUpCustomHarvesterPlan()

        then:
        1 * planService.find(_) >> plan
        1 * planService.save(plan)
        plan.customCpu && plan.customCores && plan.customMaxMemory && plan.customMaxStorage
        plan.customMaxDataStorage && plan.addVolumes
        plan.maxCores == 4L
        plan.coresPerSocket == 1L
        plan.getConfigProperty('harvesterCustomPlan') == 'all-custom'
    }

    def "setUpCustomHarvesterPlan renames the old custom plan name"() {
        given:
        def planService = Mock((Class) Class.forName('com.morpheusdata.core.synchronous.MorpheusSynchronousServicePlanService'))
        services.getServicePlan() >> planService
        def plan = new com.morpheusdata.model.ServicePlan(code: 'custom-harvester', name: '2 CPU, 4GB Memory')
        plan.setConfigProperty('harvesterCustomPlan', 'all-custom')

        when:
        provider.setUpCustomHarvesterPlan()

        then:
        1 * planService.find(_) >> plan
        1 * planService.save(plan)
        plan.name == 'Custom'
        plan.sortOrder == 100
    }

    def "setUpCustomHarvesterPlan keeps changes made after it ran"() {
        given:
        def planService = Mock((Class) Class.forName('com.morpheusdata.core.synchronous.MorpheusSynchronousServicePlanService'))
        services.getServicePlan() >> planService
        def plan = new com.morpheusdata.model.ServicePlan(code: 'custom-harvester', name: 'Custom')
        plan.customCpu = false
        plan.setConfigProperty('harvesterCustomPlan', 'all-custom')

        when:
        provider.setUpCustomHarvesterPlan()

        then:
        1 * planService.find(_) >> plan
        0 * planService.save(_)
        plan.customCpu == false
    }

    def "useDefaultPlanCpuLayout sets older fixed plans to 2 sockets once"() {
        given:
        def planService = Mock((Class) Class.forName('com.morpheusdata.core.synchronous.MorpheusSynchronousServicePlanService'))
        services.getServicePlan() >> planService
        def old = new com.morpheusdata.model.ServicePlan(code: 'harvester-vm-4cpu-16gb', name: '4 CPU, 16GB Memory',
                maxCores: 4L, maxCpu: 1L, coresPerSocket: 4L)
        def marked = new com.morpheusdata.model.ServicePlan(code: 'harvester-vm-8cpu-32gb', maxCores: 8L, coresPerSocket: 8L)
        marked.setConfigProperty('harvesterCpuLayout', '2-socket')
        planService.find(_) >> { args ->
            def codes = args[0].filters*.value
            codes.contains('harvester-vm-4cpu-16gb') ? old : (codes.contains('harvester-vm-8cpu-32gb') ? marked : null)
        }

        when:
        provider.useDefaultPlanCpuLayout()

        then:
        1 * planService.save(old)
        0 * planService.save(marked)
        old.coresPerSocket == 2L
        old.maxCpu == 2L
        old.getConfigProperty('harvesterCpuLayout') == '2-socket'
        marked.coresPerSocket == 8L
    }

    def "clusterHealthResponse reports problems as a Warning with the reason"() {
        given:
        def client = Mock(HarvesterApiClient)
        client.get('/api/v1/nodes') >> ServiceResponse.success([items: [
                [metadata: [name: 'n1'], status: [conditions: [[type: 'Ready', status: 'True']]]],
                [metadata: [name: 'n2'], status: [conditions: [[type: 'Ready', status: 'Unknown']]]]]])
        client.get(_ as String) >> ServiceResponse.error('not found')
        client.get(_ as String, _ as Map) >> ServiceResponse.error('not found')

        when:
        def response = provider.clusterHealthResponse(cloud([apiKey: 'token-a:b']), client)

        then:
        !response.success
        response.data.status == Cloud.Status.warning
        response.msg == 'Harvester health: 1 of 2 nodes not ready (n2)'
    }

    def "clusterHealthResponse is OK when healthy or switched off"() {
        given:
        def client = Mock(HarvesterApiClient)
        client.get(_ as String) >> ServiceResponse.success([items: []])
        client.get(_ as String, _ as Map) >> ServiceResponse.success([])

        expect:
        provider.clusterHealthResponse(cloud([apiKey: 'token-a:b']), client).success
        provider.clusterHealthResponse(cloud([apiKey: 'token-a:b', clusterHealth: 'off']), Mock(HarvesterApiClient)).success
    }
}
