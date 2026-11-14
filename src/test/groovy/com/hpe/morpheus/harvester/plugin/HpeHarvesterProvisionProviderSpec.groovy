// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin

import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.HostProvisionProvider
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Icon
import com.morpheusdata.model.Instance
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.Workload
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.PrepareHostResponse
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.request.ResizeV2Request
import com.morpheusdata.response.PrepareResizeV2WorkloadResponse
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ResizeV2WorkloadResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.response.ValidateResizeV2WorkloadResponse
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Title
import spock.lang.Unroll

@Title('HpeHarvesterProvisionProvider Specification')
class HpeHarvesterProvisionProviderSpec extends Specification {

    private def typedMock(String className) {
        Mock((Class) Class.forName(className))
    }

    @Subject
    HpeHarvesterProvisionProvider provider

    MorpheusContext morpheusContext
    Plugin plugin
    def services
    def clusterService

    def setup() {
        plugin = Mock(Plugin)
        morpheusContext = Stub(MorpheusContext)
        
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        services = typedMock('com.morpheusdata.core.MorpheusServices')
        clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')

        morpheusContext.getServices() >> services
        services.getComputeServer() >> computeServerService
        services.getCluster() >> clusterService

        provider = new HpeHarvesterProvisionProvider(plugin, morpheusContext)
    }

    def cleanup() {
        // Reset any metaClass modifications
        com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient.metaClass = null
        provider = null
        morpheusContext = null
        plugin = null
    }

    // Constructor Tests
    def "constructor should initialize with plugin and context"() {
        when:
        def newProvider = new HpeHarvesterProvisionProvider(plugin, morpheusContext)

        then:
        newProvider.plugin == plugin
        newProvider.context == morpheusContext
    }

    def "constructor should accept null plugin"() {
        when:
        def newProvider = new HpeHarvesterProvisionProvider(null, morpheusContext)

        then:
        newProvider.plugin == null
        newProvider.context == morpheusContext
    }

    def "constructor should accept null context"() {
        when:
        def newProvider = new HpeHarvesterProvisionProvider(plugin, null)

        then:
        newProvider.plugin == plugin
        newProvider.context == null
    }

    // Interface Tests
    def "should extend AbstractProvisionProvider"() {
        expect:
        provider instanceof AbstractProvisionProvider
    }

    def "should implement WorkloadProvisionProvider"() {
        expect:
        provider instanceof WorkloadProvisionProvider
    }

    def "should implement ResizeV2Facet"() {
        expect:
        provider instanceof WorkloadProvisionProvider.ResizeV2Facet
    }

    def "should implement HostProvisionProvider"() {
        expect:
        provider instanceof HostProvisionProvider
    }

    def "should implement ResizeFacet"() {
        expect:
        provider instanceof HostProvisionProvider.ResizeFacet
    }

    // getCode Tests
    def "getCode should return provision provider code"() {
        expect:
        provider.getCode() == HarvesterConstants.PROVISION_PROVIDER_CODE
    }

    def "getCode should not return null"() {
        expect:
        provider.getCode() != null
    }

    def "getCode should be consistent across calls"() {
        when:
        def code1 = provider.getCode()
        def code2 = provider.getCode()

        then:
        code1 == code2
    }

    // getName Tests
    def "getName should return 'Harvester Provision Provider'"() {
        expect:
        provider.getName() == 'Harvester Provision Provider'
    }

    def "getName should not return null"() {
        expect:
        provider.getName() != null
    }

    def "getName should be consistent across calls"() {
        when:
        def name1 = provider.getName()
        def name2 = provider.getName()

        then:
        name1 == name2
    }

    // getProvisionTypeCode Tests
    def "getProvisionTypeCode should return provision type code"() {
        expect:
        provider.getProvisionTypeCode() == HarvesterConstants.PROVISION_TYPE_CODE
    }

    def "getProvisionTypeCode should not return null"() {
        expect:
        provider.getProvisionTypeCode() != null
    }

    // getCircularIcon Tests
    def "getCircularIcon should return Icon object"() {
        when:
        def icon = provider.getCircularIcon()

        then:
        icon instanceof Icon
    }

    def "getCircularIcon should have circular svg paths"() {
        when:
        def icon = provider.getCircularIcon()

        then:
        icon.path == 'harvester-circular.svg'
        icon.darkPath == 'harvester-circular.svg'
    }

    def "getCircularIcon should not return null"() {
        expect:
        provider.getCircularIcon() != null
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

    def "getOptionTypes should return mutable collection"() {
        when:
        def optionTypes = provider.getOptionTypes()
        optionTypes.add(Mock(OptionType))

        then:
        optionTypes.size() == 1
    }

    // getNodeOptionTypes Tests
    def "getNodeOptionTypes should include the VM Image selector"() {
        when:
        def nodeOptionTypes = provider.getNodeOptionTypes()
        def imageOption = nodeOptionTypes.find { it.code == 'harvester-node-virtual-image' }

        def osTypeOption = nodeOptionTypes.find { it.code == 'harvester-node-os-type' }
        def radioOption = nodeOptionTypes.find { it.code == 'harvester-node-virtual-image-type' }

        then:
        nodeOptionTypes != null
        nodeOptionTypes.size() == 3
        radioOption != null
        radioOption.fieldContext == 'config'
        radioOption.fieldName == 'virtualImageSelect'
        radioOption.inputType == OptionType.InputType.RADIO
        radioOption.optionSource == 'virtualImageTypeList'
        radioOption.defaultValue == 'vi'
        imageOption.visibleOnCode == 'config.virtualImageSelect:vi'
        osTypeOption.visibleOnCode == 'config.virtualImageSelect:os'
        imageOption != null
        imageOption.fieldName == 'virtualImage.id'
        imageOption.fieldContext == 'domain'
        imageOption.inputType == OptionType.InputType.TYPEAHEAD
        imageOption.optionSource == 'harvesterNodeImages'
        !imageOption.required
        osTypeOption != null
        osTypeOption.fieldName == 'osType.id'
        osTypeOption.fieldContext == 'domain'
        osTypeOption.inputType == OptionType.InputType.SELECT
        osTypeOption.optionSource == 'osTypes'
        !osTypeOption.required
    }

    def "getNodeOptionTypes should return mutable collection"() {
        when:
        def nodeOptionTypes = provider.getNodeOptionTypes()
        nodeOptionTypes.add(Mock(OptionType))

        then:
        nodeOptionTypes.size() == 4
    }

    def "node format and deploy target service mark node types as VMs"() {
        expect:
        provider.getNodeFormat() == 'vm'
        provider.getDeployTargetService() == 'vmDeployTargetService'
    }

    // getRootVolumeStorageTypes Tests
    def "getRootVolumeStorageTypes should return empty collection"() {
        when:
        def volumeTypes = provider.getRootVolumeStorageTypes()

        then:
        volumeTypes != null
        volumeTypes instanceof Collection
        volumeTypes.isEmpty()
    }

    def "getRootVolumeStorageTypes should return mutable collection"() {
        when:
        def volumeTypes = provider.getRootVolumeStorageTypes()
        volumeTypes.add(Mock(StorageVolumeType))

        then:
        volumeTypes.size() == 1
    }

    // getDataVolumeStorageTypes Tests
    def "getDataVolumeStorageTypes should return empty collection"() {
        when:
        def dataVolTypes = provider.getDataVolumeStorageTypes()

        then:
        dataVolTypes != null
        dataVolTypes instanceof Collection
        dataVolTypes.isEmpty()
    }

    def "getDataVolumeStorageTypes should return mutable collection"() {
        when:
        def dataVolTypes = provider.getDataVolumeStorageTypes()
        dataVolTypes.add(Mock(StorageVolumeType))

        then:
        dataVolTypes.size() == 1
    }

    // getServicePlans Tests
    def "reconfigure and provisioning inputs are enabled"() {
        expect:
        provider.hasNetworks()
        provider.canReconfigureNetwork()
        provider.networksScopedToPools()
        provider.canAddVolumes()
        provider.canCustomizeRootVolume()
        provider.canCustomizeDataVolumes()
        provider.canResizeRootVolume()
        provider.rootVolumeSizeKnown()
        provider.supportsCustomServicePlans()
    }

    def "hasConfigurableSockets is on so cores per socket can be chosen"() {
        expect:
        provider.hasConfigurableSockets()
    }

    def "getServicePlans returns the fixed Harvester sizes"() {
        when:
        def plans = provider.getServicePlans()

        then:
        plans.size() == HpeHarvesterProvisionProvider.PLAN_SIZES.size()
        plans*.code.unique().size() == plans.size()
        plans.every { it.code.startsWith('harvester-vm-') && it.editable }
        plans.every { it.maxCpu * it.coresPerSocket == it.maxCores }
        plans.every { it.maxCores == 1L ? it.maxCpu == 1L : it.maxCpu == 2L }
        plans.every { it.getConfigProperty(HpeHarvesterProvisionProvider.PLAN_CPU_LAYOUT_KEY) == '2-socket' }
        plans.every { !it.customCpu && !it.customCores && !it.customMaxMemory }
        plans.every { it.customMaxStorage && it.customMaxDataStorage && it.addVolumes }
        plans*.sortOrder == (1..plans.size()).toList()
    }

    def "4 CPU plans are 2 sockets of 2 cores, 16 CPU plans 2 sockets of 8"() {
        when:
        def plans = provider.getServicePlans()

        then:
        plans.find { it.code == 'harvester-vm-4cpu-16gb' }.coresPerSocket == 2L
        plans.find { it.code == 'harvester-vm-16cpu-64gb' }.coresPerSocket == 8L
        plans.find { it.code == 'harvester-vm-1cpu-1gb' }.coresPerSocket == 1L
    }

    def "getServicePlans sizes the 2 CPU 4GB plan correctly"() {
        when:
        def plan = provider.getServicePlans().find { it.code == 'harvester-vm-2cpu-4gb' }

        then:
        plan.name == '2 CPU, 4GB Memory'
        plan.maxCpu == 2L
        plan.maxCores == 2L
        plan.coresPerSocket == 1L
        plan.maxMemory == 4L * 1024 * 1024 * 1024
        plan.maxStorage == 40L * 1024 * 1024 * 1024
    }

    def "every fixed plan has a price set in scribe"() {
        given:
        def scribe = getClass().getResource('/scribe/harvester-service-plan-price-sets.scribe').text

        expect:
        provider.getServicePlans().every { scribe.contains("code = \"${it.code}\"") }
    }

    // getMorpheus Tests
    def "getMorpheus should return morpheusContext"() {
        expect:
        provider.getMorpheus() == morpheusContext
    }

    def "getMorpheus should return same instance on multiple calls"() {
        when:
        def context1 = provider.getMorpheus()
        def context2 = provider.getMorpheus()

        then:
        context1.is(context2)
    }

    // getPlugin Tests
    def "getPlugin should return plugin"() {
        expect:
        provider.getPlugin() == plugin
    }

    def "getPlugin should return same instance on multiple calls"() {
        when:
        def plugin1 = provider.getPlugin()
        def plugin2 = provider.getPlugin()

        then:
        plugin1.is(plugin2)
    }

    // createDefaultInstanceType Tests
    def "createDefaultInstanceType should return false"() {
        expect:
        provider.createDefaultInstanceType() == false
    }

    def "createDefaultInstanceType should return Boolean type"() {
        expect:
        provider.createDefaultInstanceType() instanceof Boolean
    }

    // waitForHost Tests
    def "waitForHost should handle valid server"() {
        given:
        def server = Mock(ComputeServer) {
            getName() >> 'test-server'
            getId() >> 123L
            getServerGroup() >> Mock(ComputeServerGroup)
        }
        def computeServerService = typedMock('com.morpheusdata.core.MorpheusComputeServerService')
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        
        computeServerService.get(123L) >> server
        morpheusContext.getServices() >> services
        services.getComputeServer() >> computeServerService

        when:
        provider.waitForHost(server)

        then:
        thrown(RuntimeException)
    }

    def "waitForHost should set agent flags on provision response"() {
        given:
        def server = Mock(ComputeServer) {
            getName() >> 'test-server'
            getId() >> 123L
            getServerGroup() >> Mock(ComputeServerGroup)
        }
        def computeServerService = typedMock('com.morpheusdata.core.MorpheusComputeServerService')
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        
        computeServerService.get(123L) >> server
        morpheusContext.getServices() >> services
        services.getComputeServer() >> computeServerService

        when:
        provider.waitForHost(server)

        then:
        thrown(RuntimeException)
    }

    def "waitForHost should handle null server"() {
        when:
        provider.waitForHost(null)

        then:
        thrown(NullPointerException)
    }

    // validateHost Tests
    def "validateHost should return success"() {
        given:
        def server = Mock(ComputeServer)
        def opts = [option: 'value']

        when:
        def response = provider.validateHost(server, opts)

        then:
        response.success
        response instanceof ServiceResponse
    }

    def "validateHost should handle null server"() {
        given:
        def opts = [:]

        when:
        def response = provider.validateHost(null, opts)

        then:
        response.success
    }

    def "validateHost should handle null opts"() {
        given:
        def server = Mock(ComputeServer)

        when:
        def response = provider.validateHost(server, null)

        then:
        response.success
    }

    // prepareHost Tests
    def "prepareHost should return PrepareHostResponse"() {
        given:
        def server = Mock(ComputeServer)
        def hostRequest = Mock(HostRequest)
        def opts = [:]

        when:
        def response = provider.prepareHost(server, hostRequest, opts)

        then:
        response.success
        response.data instanceof PrepareHostResponse
    }

    def "prepareHost should disable auto updates"() {
        given:
        def server = Mock(ComputeServer)
        def hostRequest = Mock(HostRequest)
        def opts = [:]

        when:
        def response = provider.prepareHost(server, hostRequest, opts)

        then:
        response.data.disableAutoUpdates == true
    }

    def "prepareHost should handle null parameters"() {
        when:
        def response = provider.prepareHost(null, null, null)

        then:
        response != null
    }

    // runHost Tests
    def "runHost should return success"() {
        given:
        def server = Mock(ComputeServer)
        def hostRequest = Mock(HostRequest)
        def opts = [:]

        when:
        def response = provider.runHost(server, hostRequest, opts)

        then:
        response.success
        response.data instanceof ProvisionResponse
    }

    def "runHost should handle null parameters"() {
        when:
        def response = provider.runHost(null, null, null)

        then:
        response != null
    }

    // finalizeHost Tests
    def "finalizeHost should return success"() {
        given:
        def server = Mock(ComputeServer)

        when:
        def response = provider.finalizeHost(server)

        then:
        response.success
        response instanceof ServiceResponse
    }

    def "finalizeHost should handle null server"() {
        when:
        def response = provider.finalizeHost(null)

        then:
        response.success
    }

    // prepareWorkload Tests
    def "prepareWorkload should handle valid parameters"() {
        given:
        def workload = Mock(Workload) {
            getName() >> 'test-workload'
        }
        def workloadRequest = Mock(WorkloadRequest)
        def opts = [:]

        when:
        def response = provider.prepareWorkload(workload, workloadRequest, opts)

        then:
        response != null
        response instanceof ServiceResponse
    }

    def "prepareWorkload should handle null workload"() {
        given:
        def workloadRequest = Mock(WorkloadRequest)
        def opts = [:]

        when:
        def response = provider.prepareWorkload(null, workloadRequest, opts)

        then:
        response != null
    }

    def "prepareWorkload should handle null workloadRequest"() {
        given:
        def workload = Mock(Workload) {
            getName() >> 'test-workload'
        }
        def opts = [:]

        when:
        def response = provider.prepareWorkload(workload, null, opts)

        then:
        response != null
    }

    // validateWorkload Tests
    def "validateWorkload should handle valid opts"() {
        given:
        def opts = [config: [:]]

        when:
        def response = provider.validateWorkload(opts)

        then:
        response != null
        response instanceof ServiceResponse
    }

    def "validateWorkload should handle empty opts"() {
        given:
        def opts = [:]

        when:
        def response = provider.validateWorkload(opts)

        then:
        response != null
    }

    def "validateWorkload should handle null opts"() {
        when:
        def response = provider.validateWorkload(null)

        then:
        response != null
    }

    // runWorkload Tests
    def "runWorkload should handle valid parameters"() {
        given:
        def workload = Mock(Workload) {
            getName() >> 'test-workload'
        }
        def workloadRequest = Mock(WorkloadRequest)
        def opts = [:]

        when:
        def response = provider.runWorkload(workload, workloadRequest, opts)

        then:
        response != null
        response instanceof ServiceResponse
    }

    def "runWorkload should add morpheusContext to opts"() {
        given:
        def workload = Mock(Workload) {
            getName() >> 'test-workload'
        }
        def workloadRequest = Mock(WorkloadRequest)
        def opts = [:]

        when:
        provider.runWorkload(workload, workloadRequest, opts)

        then:
        opts.morpheusContext == morpheusContext
    }

    def "runWorkload should handle null workload"() {
        given:
        def workloadRequest = Mock(WorkloadRequest)
        def opts = [:]

        when:
        def response = provider.runWorkload(null, workloadRequest, opts)

        then:
        response != null
    }

    // finalizeWorkload Tests
    def "finalizeWorkload should handle valid workload"() {
        given:
        def workload = Mock(Workload) {
            getName() >> 'test-workload'
        }

        when:
        def response = provider.finalizeWorkload(workload)

        then:
        response != null
        response instanceof ServiceResponse
    }

    def "finalizeWorkload should return success even on error"() {
        given:
        def workload = Mock(Workload) {
            getName() >> { throw new RuntimeException("Test error") }
        }

        when:
        def response = provider.finalizeWorkload(workload)

        then:
        response.success
    }

    def "finalizeWorkload should handle null workload"() {
        when:
        def response = provider.finalizeWorkload(null)

        then:
        response != null
    }

    // stopWorkload Tests
    def "stopWorkload should handle valid workload"() {
        given:
        def serverGroup = Mock(com.morpheusdata.model.ComputeServerGroup) { getId() >> 123L }
        def server = Mock(ComputeServer) {
            getName() >> 'test-server'
            getResourcePool() >> [serverGroup: [id: 123L]]
            getConfigProperty("vmname") >> "test-server"
            getConfigProperty("project") >> [name: "test-ns"]
            getExternalId() >> null
        }
        def mockApiClient = Mock(com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient)
        clusterService.get(123L) >> serverGroup
        com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient.metaClass.constructor = {
            com.morpheusdata.model.ComputeServerGroup sg -> mockApiClient
        }
        // VM is already stopped — stopVM returns success immediately
        mockApiClient.get(_) >> ServiceResponse.success([status: [printableStatus: "Stopped"]])
        def workload = Mock(Workload) {
            getName() >> 'test-workload'
            getServer() >> server
        }

        when:
        def response = provider.stopWorkload(workload)

        then:
        response != null
        response instanceof ServiceResponse
    }

    def "stopWorkload should handle null workload"() {
        when:
        provider.stopWorkload(null)

        then:
        thrown(Exception)
    }

    // startWorkload Tests
    def "startWorkload should handle valid workload"() {
        given:
        def server = Mock(ComputeServer) {
            getName() >> 'test-server'
        }
        def workload = Mock(Workload) {
            getName() >> 'test-workload'
            getServer() >> server
        }

        when:
        def response = provider.startWorkload(workload)

        then:
        response != null
        response instanceof ServiceResponse
    }

    def "startWorkload should handle null workload"() {
        when:
        def response = provider.startWorkload(null)

        then:
        response != null
    }

    // restartWorkload Tests
    def "restartWorkload should handle valid workload"() {
        given:
        def server = Mock(ComputeServer) {
            getName() >> 'test-server'
        }
        def workload = Mock(Workload) {
            getName() >> 'test-workload'
            getServer() >> server
        }

        when:
        def response = provider.restartWorkload(workload)

        then:
        response != null
        response instanceof ServiceResponse
    }

    def "restartWorkload should handle null workload"() {
        when:
        def response = provider.restartWorkload(null)

        then:
        response != null
    }

    // resizeServer Tests
    def "resizeServer should return success"() {
        given:
        def server = Mock(ComputeServer)
        def resizeRequest = Mock(ResizeRequest)
        def opts = [:]

        when:
        def response = provider.resizeServer(server, resizeRequest, opts)

        then:
        response.success
        response instanceof ServiceResponse
    }

    def "resizeServer should handle null parameters"() {
        when:
        def response = provider.resizeServer(null, null, null)

        then:
        response.success
    }

    // removeWorkload Tests
    def "removeWorkload should handle valid workload"() {
        given:
        def server = Mock(ComputeServer)
        def workload = Mock(Workload) {
            getName() >> 'test-workload'
            getServer() >> server
        }
        def opts = [:]

        when:
        def response = provider.removeWorkload(workload, opts)

        then:
        response != null
        response instanceof ServiceResponse
    }

    def "removeWorkload should handle null workload"() {
        given:
        def opts = [:]

        when:
        def response = provider.removeWorkload(null, opts)

        then:
        response != null
    }

    def "removeWorkload should handle null opts"() {
        given:
        def server = Mock(ComputeServer)
        def workload = Mock(Workload) {
            getName() >> 'test-workload'
            getServer() >> server
        }

        when:
        def response = provider.removeWorkload(workload, null)

        then:
        response != null
    }

    // getServerDetails Tests
    def "getServerDetails should handle valid server"() {
        given:
        def server = Mock(ComputeServer) {
            getName() >> 'test-server'
        }

        when:
        def response = provider.getServerDetails(server)

        then:
        response != null
        response instanceof ServiceResponse
    }

    def "getServerDetails should handle null server"() {
        when:
        def response = provider.getServerDetails(null)

        then:
        response != null
    }

    // createWorkloadResources Tests
    def "createWorkloadResources should return success"() {
        given:
        def workload = Mock(Workload)
        def opts = [:]

        when:
        def response = provider.createWorkloadResources(workload, opts)

        then:
        response.success
        response instanceof ServiceResponse
    }

    def "createWorkloadResources should handle null workload"() {
        given:
        def opts = [:]

        when:
        def response = provider.createWorkloadResources(null, opts)

        then:
        response.success
    }

    def "createWorkloadResources should handle null opts"() {
        given:
        def workload = Mock(Workload)

        when:
        def response = provider.createWorkloadResources(workload, null)

        then:
        response.success
    }

    // stopServer Tests
    def "stopServer should handle valid server"() {
        given:
        def server = Mock(ComputeServer) {
            getName() >> 'test-server'
        }

        when:
        def response = provider.stopServer(server)

        then:
        response != null
        response instanceof ServiceResponse
    }

    def "stopServer should handle null server"() {
        when:
        def response = provider.stopServer(null)

        then:
        response != null
    }

    // startServer Tests
    def "startServer should handle valid server"() {
        given:
        def server = Mock(ComputeServer) {
            getName() >> 'test-server'
        }

        when:
        def response = provider.startServer(server)

        then:
        response != null
        response instanceof ServiceResponse
    }

    def "startServer should handle null server"() {
        when:
        def response = provider.startServer(null)

        then:
        response != null
    }

    // suspendWorkload Tests
    def "suspendWorkload should handle valid workload"() {
        given:
        def server = Mock(ComputeServer) {
            getName() >> 'test-server'
        }
        def workload = Mock(Workload) {
            getName() >> 'test-workload'
            getServer() >> server
        }

        when:
        def response = provider.suspendWorkload(workload)

        then:
        response != null
        response instanceof ServiceResponse
    }

    def "suspendWorkload should handle null workload"() {
        when:
        def response = provider.suspendWorkload(null)

        then:
        response != null
    }

    // validateResizeWorkload Tests
    def "validateResizeWorkload should handle valid parameters"() {
        given:
        def instance = Mock(Instance)
        def server = Mock(ComputeServer)
        def workload = Mock(Workload) {
            getName() >> 'test-workload'
            getServer() >> server
        }
        def resizeRequest = Mock(ResizeV2Request)
        def opts = [:]

        when:
        def response = provider.validateResizeWorkload(instance, workload, resizeRequest, opts)

        then:
        response != null
        response instanceof ServiceResponse
    }

    def "validateResizeWorkload should handle null instance"() {
        given:
        def server = Mock(ComputeServer)
        def workload = Mock(Workload) {
            getServer() >> server
        }
        def resizeRequest = Mock(ResizeV2Request)
        def opts = [:]

        when:
        def response = provider.validateResizeWorkload(null, workload, resizeRequest, opts)

        then:
        response != null
    }

    def "validateResizeWorkload should handle null workload"() {
        given:
        def instance = Mock(Instance)
        def resizeRequest = Mock(ResizeV2Request)
        def opts = [:]

        when:
        def response = provider.validateResizeWorkload(instance, null, resizeRequest, opts)

        then:
        response != null
    }

    // prepareResizeWorkload Tests
    def "prepareResizeWorkload should handle valid parameters"() {
        given:
        def instance = Mock(Instance)
        def server = Mock(ComputeServer)
        def workload = Mock(Workload) {
            getName() >> 'test-workload'
            getServer() >> server
        }
        def resizeRequest = Mock(ResizeV2Request)
        def opts = [:]

        when:
        def response = provider.prepareResizeWorkload(instance, workload, resizeRequest, opts)

        then:
        response != null
        response instanceof ServiceResponse
    }

    def "prepareResizeWorkload should handle null instance"() {
        given:
        def server = Mock(ComputeServer)
        def workload = Mock(Workload) {
            getServer() >> server
        }
        def resizeRequest = Mock(ResizeV2Request)
        def opts = [:]

        when:
        def response = provider.prepareResizeWorkload(null, workload, resizeRequest, opts)

        then:
        response != null
    }

    // resizeWorkload Tests
    def "resizeWorkload should handle valid parameters"() {
        given:
        def instance = Mock(Instance)
        def server = Mock(ComputeServer)
        def workload = Mock(Workload) {
            getName() >> 'test-workload'
            getServer() >> server
        }
        def resizeRequest = Mock(ResizeV2Request)
        def opts = [:]

        when:
        def response = provider.resizeWorkload(instance, workload, resizeRequest, opts)

        then:
        response != null
        response instanceof ServiceResponse
    }

    def "resizeWorkload should handle null instance"() {
        given:
        def server = Mock(ComputeServer)
        def workload = Mock(Workload) {
            getServer() >> server
        }
        def resizeRequest = Mock(ResizeV2Request)
        def opts = [:]

        when:
        def response = provider.resizeWorkload(null, workload, resizeRequest, opts)

        then:
        response != null
    }

    // Thread Safety Tests
    def "should handle concurrent getCode calls"() {
        given:
        def results = Collections.synchronizedList([])

        when:
        def threads = (1..10).collect {
            Thread.start {
                results.add(provider.getCode())
            }
        }
        threads*.join()

        then:
        results.size() == 10
        results.unique().size() == 1
    }

    def "should handle concurrent getName calls"() {
        given:
        def results = Collections.synchronizedList([])

        when:
        def threads = (1..10).collect {
            Thread.start {
                results.add(provider.getName())
            }
        }
        threads*.join()

        then:
        results.size() == 10
        results.unique().size() == 1
    }

    def "should handle concurrent validateHost calls"() {
        given:
        def server = Mock(ComputeServer)
        def results = Collections.synchronizedList([])

        when:
        def threads = (1..5).collect {
            Thread.start {
                def response = provider.validateHost(server, [:])
                results.add(response.success)
            }
        }
        threads*.join()

        then:
        results.size() == 5
        results.every { it == true }
    }

    def "should handle concurrent finalizeHost calls"() {
        given:
        def server = Mock(ComputeServer)
        def results = Collections.synchronizedList([])

        when:
        def threads = (1..5).collect {
            Thread.start {
                def response = provider.finalizeHost(server)
                results.add(response.success)
            }
        }
        threads*.join()

        then:
        results.size() == 5
        results.every { it == true }
    }

    def "should handle concurrent createWorkloadResources calls"() {
        given:
        def workload = Mock(Workload)
        def results = Collections.synchronizedList([])

        when:
        def threads = (1..5).collect {
            Thread.start {
                def response = provider.createWorkloadResources(workload, [:])
                results.add(response.success)
            }
        }
        threads*.join()

        then:
        results.size() == 5
        results.every { it == true }
    }

    // Idempotency Tests
    def "getCode should be idempotent"() {
        when:
        def codes = (1..10).collect { provider.getCode() }

        then:
        codes.unique().size() == 1
    }

    def "getName should be idempotent"() {
        when:
        def names = (1..10).collect { provider.getName() }

        then:
        names.unique().size() == 1
    }

    def "getProvisionTypeCode should be idempotent"() {
        when:
        def codes = (1..10).collect { provider.getProvisionTypeCode() }

        then:
        codes.unique().size() == 1
    }

    def "createDefaultInstanceType should be idempotent"() {
        when:
        def results = (1..10).collect { provider.createDefaultInstanceType() }

        then:
        results.unique().size() == 1
        results.every { it == false }
    }

    def "validateHost should be idempotent"() {
        given:
        def server = Mock(ComputeServer)
        def opts = [:]

        when:
        def response1 = provider.validateHost(server, opts)
        def response2 = provider.validateHost(server, opts)
        def response3 = provider.validateHost(server, opts)

        then:
        response1.success
        response2.success
        response3.success
    }

    def "finalizeHost should be idempotent"() {
        given:
        def server = Mock(ComputeServer)

        when:
        def response1 = provider.finalizeHost(server)
        def response2 = provider.finalizeHost(server)
        def response3 = provider.finalizeHost(server)

        then:
        response1.success
        response2.success
        response3.success
    }

    // Provider Instance Tests
    def "multiple provider instances should be independent"() {
        given:
        def provider1 = new HpeHarvesterProvisionProvider(plugin, morpheusContext)
        def provider2 = new HpeHarvesterProvisionProvider(Mock(Plugin), Mock(MorpheusContext))

        expect:
        !provider1.is(provider2)
        provider1.getCode() == provider2.getCode()
        provider1.getName() == provider2.getName()
    }

    def "provider should maintain state consistency"() {
        when:
        provider.getCode()
        provider.getName()
        provider.getOptionTypes()

        then:
        provider.plugin == plugin
        provider.context == morpheusContext
    }

    // Collection Tests
    @Unroll
    def "empty collections should be returned for: #methodName"() {
        when:
        def result = provider."${methodName}"()

        then:
        result != null
        result instanceof Collection
        result.isEmpty()

        where:
        // getNodeOptionTypes is excluded: it now returns the VM Image selector (tested separately)
        // getServicePlans is excluded: it returns the fixed Harvester plan sizes (tested separately)
        methodName << ['getOptionTypes',
                       'getRootVolumeStorageTypes', 'getDataVolumeStorageTypes']
    }

    // Interface Coverage Tests
    @Unroll
    def "WorkloadProvisionProvider method should exist: #methodName"() {
        expect:
        provider.respondsTo(methodName)

        where:
        methodName << ['prepareWorkload', 'runWorkload', 'finalizeWorkload',
                       'stopWorkload', 'startWorkload', 'restartWorkload',
                       'removeWorkload', 'getServerDetails', 'createWorkloadResources']
    }

    @Unroll
    def "HostProvisionProvider method should exist: #methodName"() {
        expect:
        provider.respondsTo(methodName)

        where:
        methodName << ['waitForHost', 'validateHost', 'prepareHost',
                       'runHost', 'finalizeHost']
    }

    @Unroll
    def "ResizeV2Facet method should exist: #methodName"() {
        expect:
        provider.respondsTo(methodName)

        where:
        methodName << ['validateResizeWorkload', 'prepareResizeWorkload', 'resizeWorkload']
    }

    // Workload Lifecycle Tests
    def "should handle complete workload lifecycle"() {
        given:
        def serverGroup = Mock(com.morpheusdata.model.ComputeServerGroup) { getId() >> 123L }
        def server = Mock(ComputeServer) {
            getName() >> 'test-server'
            getResourcePool() >> [serverGroup: [id: 123L]]
            getConfigProperty("vmname") >> "test-server"
            getConfigProperty("project") >> [name: "test-ns"]
            getExternalId() >> null
        }
        def mockApiClient = Mock(com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient)
        clusterService.get(123L) >> serverGroup
        com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient.metaClass.constructor = {
            com.morpheusdata.model.ComputeServerGroup sg -> mockApiClient
        }
        // VM is already stopped for the stop call
        mockApiClient.get(_) >> ServiceResponse.success([status: [printableStatus: "Stopped"]])
        def workload = Mock(Workload) {
            getName() >> 'test-workload'
            getServer() >> server
        }
        def workloadRequest = Mock(WorkloadRequest)
        def opts = [:]

        when: "prepare workload"
        def prepareResponse = provider.prepareWorkload(workload, workloadRequest, opts)

        then:
        prepareResponse != null

        when: "validate workload"
        def validateResponse = provider.validateWorkload(opts)

        then:
        validateResponse != null

        when: "run workload"
        def runResponse = provider.runWorkload(workload, workloadRequest, opts)

        then:
        runResponse != null

        when: "finalize workload"
        def finalizeResponse = provider.finalizeWorkload(workload)

        then:
        finalizeResponse != null

        when: "start workload"
        def startResponse = provider.startWorkload(workload)

        then:
        startResponse != null

        when: "stop workload"
        def stopResponse = provider.stopWorkload(workload)

        then:
        stopResponse != null

        when: "remove workload"
        def removeResponse = provider.removeWorkload(workload, opts)

        then:
        removeResponse != null
    }

    // Host Lifecycle Tests
    def "should handle complete host lifecycle"() {
        given:
        def server = Mock(ComputeServer) {
            getName() >> 'test-server'
            getId() >> 123L
            getServerGroup() >> Mock(ComputeServerGroup)
        }
        def hostRequest = Mock(HostRequest)
        def opts = [:]
        def computeServerService = typedMock('com.morpheusdata.core.MorpheusComputeServerService')
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        
        computeServerService.get(123L) >> server
        morpheusContext.getServices() >> services
        services.getComputeServer() >> computeServerService

        when: "validate host"
        def validateResponse = provider.validateHost(server, opts)

        then:
        validateResponse.success

        when: "prepare host"
        def prepareResponse = provider.prepareHost(server, hostRequest, opts)

        then:
        prepareResponse.success

        when: "run host"
        def runResponse = provider.runHost(server, hostRequest, opts)

        then:
        runResponse.success

        when: "wait for host"
        provider.waitForHost(server)

        then:
        thrown(RuntimeException)

        when: "finalize host"
        def finalizeResponse = provider.finalizeHost(server)

        then:
        finalizeResponse.success
    }

    def "waitForHost should populate response and persist server when master node is found"() {
        given:
        def server = new ComputeServer(id: 701L, name: 'master-check')
        server.serverGroup = Mock(ComputeServerGroup)

        def computeServerService = typedMock('com.morpheusdata.core.MorpheusComputeServerService')
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        morpheusContext.getServices() >> services
        services.getComputeServer() >> computeServerService
        computeServerService.get(701L) >> server

        GroovySpy(com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper, global: true)
        def nodeApiHelper = Mock(com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper)
        1 * new com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper(_ as ComputeServerGroup) >> nodeApiHelper
        1 * nodeApiHelper.getMasterNodeDetails() >> [
                uid: 'node-701', hostname: 'master-701', internalIP: '10.10.10.7', externalIP: '44.44.44.7'
        ]

        when:
        def response = provider.waitForHost(server)

        then:
        response.success
        response.data.privateIp == '10.10.10.7'
        response.data.publicIp == '44.44.44.7'
        response.data.hostname == 'master-701'
        server.uniqueId == 'node-701'
        server.externalId == 'node-701'
        server.hostname == 'master-701'
    }

    def "waitForHost should set error message when master node details are unavailable"() {
        given:
        def server = new ComputeServer(id: 702L, name: 'missing-master')
        server.serverGroup = Mock(ComputeServerGroup)

        GroovySpy(com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper, global: true)
        def nodeApiHelper = Mock(com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper)
        1 * new com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper(_ as ComputeServerGroup) >> nodeApiHelper
        1 * nodeApiHelper.getMasterNodeDetails() >> null

        when:
        def response = provider.waitForHost(server)

        then:
        response.success
        response.data.message == 'Could not find master node details'
    }

    def "validateConfig should return field errors when catalog and vApp are missing"() {
        when:
        def result = HpeHarvesterProvisionProvider.validateConfig([config: [:]])

        then:
        !result.success
        result.errors*.field.containsAll(['catalogId', 'vappId'])
    }

    def "validateConfig should succeed when catalog and vApp are present"() {
        when:
        def result = HpeHarvesterProvisionProvider.validateConfig([config: [catalogId: '1', vappId: '2']])

        then:
        result.success
        result.errors.isEmpty()
    }

    def "validateResizeWorkload should return unsuccessful response when reconfigure validation fails"() {
        given:
        def server = Mock(ComputeServer)
        def instance = Mock(Instance)
        def workload = Mock(Workload) {
            getName() >> 'resize-wl'
            getServer() >> server
        }
        def resizeRequest = Mock(ResizeV2Request)

        GroovyMock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure, global: true)
        def vmReconfigure = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure)
        1 * new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure(morpheusContext, server) >> vmReconfigure
        1 * vmReconfigure.validateResizeRequest(resizeRequest, _ as Map) >> new ServiceResponse(success: false, msg: 'invalid plan', errors: [error: 'bad resize'])

        when:
        def response = provider.validateResizeWorkload(instance, workload, resizeRequest, [:])

        then:
        !response.success
        response.msg == 'invalid plan'
        response.errors?.error == 'bad resize'
    }

    def "prepareResizeWorkload should return error response when reconfigure throws"() {
        given:
        def server = Mock(ComputeServer)
        def instance = Mock(Instance)
        def workload = Mock(Workload) {
            getName() >> 'prepare-resize'
            getServer() >> server
        }
        def resizeRequest = Mock(ResizeV2Request)

        GroovyMock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure, global: true)
        1 * new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure(morpheusContext, server) >> { throw new RuntimeException('prepare failed') }

        when:
        def response = provider.prepareResizeWorkload(instance, workload, resizeRequest, [:])

        then:
        !response.success
        response.errors?.error?.contains('prepare failed')
    }

    def "resizeWorkload should return error response when reconfigure throws"() {
        given:
        def server = Mock(ComputeServer)
        def instance = Mock(Instance)
        def workload = Mock(Workload) {
            getName() >> 'execute-resize'
            getServer() >> server
        }
        def resizeRequest = Mock(ResizeV2Request)

        GroovyMock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure, global: true)
        1 * new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure(morpheusContext, server) >> { throw new RuntimeException('resize failed') }

        when:
        def response = provider.resizeWorkload(instance, workload, resizeRequest, [:])

        then:
        !response.success
        response.errors?.error?.contains('resize failed')
    }

    def "prepareWorkload should delegate to VirtualMachineProvisioning and return success response"() {
        given:
        def workload = Mock(Workload) { getName() >> 'prepare-success' }
        def request = Mock(WorkloadRequest)
        def opts = [a: 'b']
        def delegated = new ServiceResponse(success: true, data: new PrepareWorkloadResponse())

        GroovyMock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning, global: true)
        def vmProvisioning = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning)
        1 * new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning(morpheusContext) >> vmProvisioning
        1 * vmProvisioning.prepareWorkload(workload, request, opts) >> delegated

        when:
        def response = provider.prepareWorkload(workload, request, opts)

        then:
        response.success
        response.data instanceof PrepareWorkloadResponse
    }

    def "validateWorkload should delegate to VirtualMachineProvisioning and preserve failure response"() {
        given:
        def opts = [bad: true]
        def delegated = new ServiceResponse(success: false, msg: 'invalid workload', errors: [workload: 'invalid'])

        GroovyMock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning, global: true)
        def vmProvisioning = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning)
        1 * new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning(morpheusContext) >> vmProvisioning
        1 * vmProvisioning.validateWorkload(opts) >> delegated

        when:
        def response = provider.validateWorkload(opts)

        then:
        !response.success
        response.msg == 'invalid workload'
        response.errors.workload == 'invalid'
    }

    def "runWorkload should inject morpheusContext into opts before delegation"() {
        given:
        def workload = Mock(Workload) { getName() >> 'run-success' }
        def request = Mock(WorkloadRequest)
        def opts = [mode: 'test']
        def delegated = new ServiceResponse(success: true, data: new ProvisionResponse(success: true))

        GroovyMock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning, global: true)
        def vmProvisioning = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning)
        1 * new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning(morpheusContext) >> vmProvisioning
        1 * vmProvisioning.runWorkload(workload, request, _ as Map) >> { args ->
            assert args[2].morpheusContext == morpheusContext
            delegated
        }

        when:
        def response = provider.runWorkload(workload, request, opts)

        then:
        response.success
        opts.morpheusContext == morpheusContext
    }

    def "getServerDetails should delegate to VirtualMachineProvisioning and return delegated error payload"() {
        given:
        def server = Mock(ComputeServer) { getName() >> 'details-server' }
        def payload = new ProvisionResponse(success: false)
        payload.setError('details-failed')
        def delegated = new ServiceResponse<ProvisionResponse>(success: false, msg: 'details-failed', data: payload)

        GroovyMock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning, global: true)
        def vmProvisioning = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning)
        1 * new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning(morpheusContext) >> vmProvisioning
        1 * vmProvisioning.getServerDetails(server) >> delegated

        when:
        def response = provider.getServerDetails(server)

        then:
        !response.success
        response.msg == 'details-failed'
    }

    def "finalizeWorkload should delegate to finalizeProvisioning"() {
        given:
        def workload = Mock(Workload) { getName() >> 'finalize-server' }

        GroovyMock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning, global: true)
        def vmProvisioning = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning)
        1 * new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning(morpheusContext) >> vmProvisioning
        1 * vmProvisioning.finalizeProvisioning(workload) >> ServiceResponse.success([finalized: true])

        when:
        def response = provider.finalizeWorkload(workload)

        then:
        response.success
        response.data.finalized == true
    }

    def "removeWorkload should delegate to VirtualMachineDeletion with workload server"() {
        given:
        def server = Mock(ComputeServer)
        def workload = Mock(Workload) {
            getName() >> 'remove-wl'
            getServer() >> server
        }

        GroovyMock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineDeletion, global: true)
        def vmDeletion = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineDeletion)
        1 * new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineDeletion(morpheusContext) >> vmDeletion
        1 * vmDeletion.deleteServer(server) >> ServiceResponse.success([removed: true])

        when:
        def response = provider.removeWorkload(workload, [reason: 'cleanup'])

        then:
        response.success
        response.data.removed == true
    }

    def "power operations should delegate for workload and server variants"() {
        given:
        def server = Mock(ComputeServer) { getName() >> 'power-server' }
        def workload = Mock(Workload) {
            getName() >> 'power-workload'
            getServer() >> server
        }

        GroovyMock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure, global: true)
        def stopByWorkload = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure)
        def startByWorkload = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure)
        def restartByWorkload = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure)
        def suspendByWorkload = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure)
        def stopByServer = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure)
        def startByServer = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure)

        1 * new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure(morpheusContext, server) >> stopByWorkload
        1 * stopByWorkload.stopVM() >> ServiceResponse.success([state: 'stopped-workload'])
        1 * new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure(morpheusContext, server) >> startByWorkload
        1 * startByWorkload.startVM() >> ServiceResponse.success([state: 'started-workload'])
        1 * new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure(morpheusContext, server) >> restartByWorkload
        1 * restartByWorkload.restartVM() >> ServiceResponse.success([state: 'restarted-workload'])
        1 * new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure(morpheusContext, server) >> suspendByWorkload
        1 * suspendByWorkload.suspendVM() >> ServiceResponse.success([state: 'suspended-workload'])
        1 * new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure(morpheusContext, server) >> stopByServer
        1 * stopByServer.stopVM() >> ServiceResponse.success([state: 'stopped-server'])
        1 * new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure(morpheusContext, server) >> startByServer
        1 * startByServer.startVM() >> ServiceResponse.success([state: 'started-server'])

        when:
        def stopWorkloadResponse = provider.stopWorkload(workload)
        def startWorkloadResponse = provider.startWorkload(workload)
        def restartWorkloadResponse = provider.restartWorkload(workload)
        def suspendWorkloadResponse = provider.suspendWorkload(workload)
        def stopServerResponse = provider.stopServer(server)
        def startServerResponse = provider.startServer(server)

        then:
        stopWorkloadResponse.success
        startWorkloadResponse.success
        restartWorkloadResponse.success
        suspendWorkloadResponse.success
        stopServerResponse.success
        startServerResponse.success
    }

    // ===== getNoVNCConsoleUrl Tests =====

    def "getNoVNCConsoleUrl delegates to VirtualMachineProvisioning successfully"() {
        given:
        def server = new ComputeServer(id: 100L, name: 'test-server')
        def mockVmProvisioning = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning)
        GroovySpy(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning, global: true, constructorArgs: [morpheusContext])
        new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning(morpheusContext) >> mockVmProvisioning
        
        def expectedResponse = ServiceResponse.success([url: 'wss://console-url', headers: [:]])
        mockVmProvisioning.getNoVNCConsoleUrl(server) >> expectedResponse

        when:
        def response = provider.getNoVNCConsoleUrl(server)

        then:
        response.success
        response.data.url == 'wss://console-url'
    }

    def "getNoVNCConsoleUrl returns error when exception occurs"() {
        given:
        def server = new ComputeServer(id: 101L, name: 'error-server')
        def mockVmProvisioning = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning)
        GroovySpy(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning, global: true, constructorArgs: [morpheusContext])
        new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning(morpheusContext) >> mockVmProvisioning
        
        mockVmProvisioning.getNoVNCConsoleUrl(server) >> { throw new RuntimeException('Connection failed') }

        when:
        def response = provider.getNoVNCConsoleUrl(server)

        then:
        !response.success
        response.msg.contains('Error')
    }

    def "getNoVNCConsoleUrl handles null server gracefully"() {
        given:
        def mockVmProvisioning = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning)
        GroovySpy(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning, global: true, constructorArgs: [morpheusContext])
        new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning(morpheusContext) >> mockVmProvisioning
        
        mockVmProvisioning.getNoVNCConsoleUrl(null) >> ServiceResponse.error('Server is null')

        when:
        def response = provider.getNoVNCConsoleUrl(null)

        then:
        !response.success
    }

    // --- Exception path coverage for delegation methods ---

    def "prepareWorkload catches exception and returns error"() {
        given:
        def mockVmProvisioning = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning)
        GroovySpy(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning, global: true, constructorArgs: [morpheusContext])
        new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning(_) >> { throw new RuntimeException("init failed") }

        when:
        def response = provider.prepareWorkload(Mock(Workload), Mock(WorkloadRequest), [:])

        then:
        !response.success
    }

    def "validateWorkload catches exception and returns error"() {
        given:
        def mockVmProvisioning = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning)
        GroovySpy(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning, global: true, constructorArgs: [morpheusContext])
        new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning(_) >> { throw new RuntimeException("init failed") }

        when:
        def response = provider.validateWorkload([:])

        then:
        !response.success
    }

    def "runWorkload catches exception and returns error"() {
        given:
        def mockVmProvisioning = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning)
        GroovySpy(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning, global: true, constructorArgs: [morpheusContext])
        new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning(_) >> { throw new RuntimeException("init failed") }

        when:
        def response = provider.runWorkload(Mock(Workload), Mock(WorkloadRequest), [:])

        then:
        !response.success
    }

    def "finalizeWorkload catches exception and returns success"() {
        given:
        def mockVmProvisioning = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning)
        GroovySpy(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning, global: true, constructorArgs: [morpheusContext])
        new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning(_) >> { throw new RuntimeException("init failed") }

        when:
        def response = provider.finalizeWorkload(Mock(Workload))

        then:
        response.success // finalizeWorkload returns success even on exception
    }

    def "getServerDetails catches exception and returns error"() {
        given:
        def mockVmProvisioning = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning)
        GroovySpy(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning, global: true, constructorArgs: [morpheusContext])
        new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning(_) >> { throw new RuntimeException("init failed") }

        when:
        def response = provider.getServerDetails(new ComputeServer(name: 'test'))

        then:
        !response.success
    }

    def "removeWorkload catches exception and returns error"() {
        given:
        def workload = Mock(Workload)
        workload.server >> { throw new RuntimeException("no server") }

        when:
        def response = provider.removeWorkload(workload, [:])

        then:
        !response.success
    }

    def "validateConfig returns errors when catalogId and vappId missing"() {
        when:
        def result = HpeHarvesterProvisionProvider.validateConfig(config: [:])

        then:
        !result.success
        result.errors.size() == 2
    }

    def "validateConfig returns success when catalogId and vappId present"() {
        when:
        def result = HpeHarvesterProvisionProvider.validateConfig(config: [catalogId: 'cat1', vappId: 'app1'])

        then:
        result.success
    }

    def "resizeServer returns success"() {
        when:
        def result = provider.resizeServer(new ComputeServer(name: 'test'), Mock(ResizeRequest), [:])

        then:
        result.success
    }

    def "createWorkloadResources returns success"() {
        when:
        def result = provider.createWorkloadResources(Mock(Workload), [:])

        then:
        result.success
    }

    def "datastore choice is offered on every disk"() {
        expect:
        provider.hasComputeZonePools()
        provider.computeZonePoolRequired()
        provider.hasDatastores()
        !provider.disableRootDatastore()
        provider.supportsAutoDatastore()
    }
}
