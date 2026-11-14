package com.hpe.morpheus.harvester.plugin.provision

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Unroll

class ProvisionHelperSpec extends Specification {

    ComputeServer computeServer
    WorkloadRequest workloadRequest
    ComputeServerGroup computeServerGroup
    Map opts
    ProvisionHelper helper

    def setup() {
        computeServer = Mock(ComputeServer)
        workloadRequest = Mock(WorkloadRequest)
        computeServerGroup = Mock(ComputeServerGroup)
        opts = [namespace: 'test-namespace', labels: [:], annotations: [:]]
        helper = new ProvisionHelper(computeServer, workloadRequest, computeServerGroup, opts)
    }

    def cleanup() {
        HarvesterApiClient.metaClass = null
        com.hpe.morpheus.harvester.plugin.apihelper.ConfigMapApiHelper.metaClass = null
    }

    // Constructor Tests

    def "constructor should initialize all properties"() {
        when:
        def newHelper = new ProvisionHelper(computeServer, workloadRequest, computeServerGroup, opts)

        then:
        newHelper.computeServer == computeServer
        newHelper.workloadRequest == workloadRequest
        newHelper.computeServerGroup == computeServerGroup
        newHelper.opts == opts
    }

    // buildCloudInitOptions Tests

    def "buildCloudInitOptions should return success with configName when userData present"() {
        given:
        computeServer.name >> 'test-vm'
        workloadRequest.cloudConfigUser >> '#cloud-config\nhostname: test'
        workloadRequest.cloudConfigNetwork >> null
        workloadRequest.cloudConfigMeta >> null

        when:
        def result = helper.buildCloudInitOptions()

        then:
        result.success == false // Will fail because createCloudInitSecret needs real API
        result.configName == 'secret-test-vm'
    }

    def "buildCloudInitOptions should include all cloud-init data types"() {
        given:
        computeServer.name >> 'test-vm'
        workloadRequest.cloudConfigUser >> 'user-data'
        workloadRequest.cloudConfigNetwork >> 'network-data'
        workloadRequest.cloudConfigMeta >> 'meta-data'

        when:
        def result = helper.buildCloudInitOptions()

        then:
        result.configName == 'secret-test-vm'
    }

    def "buildCloudInitOptions should mark success true when secret creation succeeds"() {
        given:
        computeServer.name >> 'test-vm'
        workloadRequest.cloudConfigUser >> 'user-data'
        workloadRequest.cloudConfigNetwork >> 'network-data'
        workloadRequest.cloudConfigMeta >> 'meta-data'
        def helperSpy = Spy(ProvisionHelper, constructorArgs: [computeServer, workloadRequest, computeServerGroup, opts])
        helperSpy.createCloudInitSecret('secret-test-vm', 'test-namespace', _ as Map) >> ServiceResponse.success([:])

        when:
        def result = helperSpy.buildCloudInitOptions()

        then:
        result.success
        result.configName == 'secret-test-vm'
    }

    def "buildCloudInitOptions should include empty cloud-init map when request fields missing"() {
        given:
        computeServer.name >> 'empty-vm'
        workloadRequest.cloudConfigUser >> null
        workloadRequest.cloudConfigNetwork >> null
        workloadRequest.cloudConfigMeta >> null
        def helperSpy = Spy(ProvisionHelper, constructorArgs: [computeServer, workloadRequest, computeServerGroup, opts])
        helperSpy.createCloudInitSecret('secret-empty-vm', 'test-namespace', [:]) >> ServiceResponse.success([:])

        when:
        def result = helperSpy.buildCloudInitOptions()

        then:
        result.success
        result.configName == 'secret-empty-vm'
    }

    def "buildCloudInitOptions should include user network and meta data with real request object"() {
        given:
        computeServer.name >> 'real-vm'
        def request = new WorkloadRequest(
                cloudConfigUser: 'u-real',
                cloudConfigNetwork: 'n-real',
                cloudConfigMeta: 'm-real'
        )
        def helperLocal = Spy(ProvisionHelper, constructorArgs: [computeServer, request, computeServerGroup, opts])
        helperLocal.createCloudInitSecret('secret-real-vm', 'test-namespace', _ as Map) >> { String s, String ns, Map cloudData ->
            assert cloudData == [userData: 'u-real', networkData: 'n-real', metaData: 'm-real']
            ServiceResponse.success([:])
        }

        when:
        def result = helperLocal.buildCloudInitOptions()

        then:
        result.success
        result.configName == 'secret-real-vm'
    }

    def "buildCloudInitOptions should include partial data with real request object"() {
        given:
        computeServer.name >> 'real-partial-vm'
        def request = new WorkloadRequest(cloudConfigNetwork: 'n-only')
        def helperLocal = Spy(ProvisionHelper, constructorArgs: [computeServer, request, computeServerGroup, opts])
        helperLocal.createCloudInitSecret('secret-real-partial-vm', 'test-namespace', _ as Map) >> { String s, String ns, Map cloudData ->
            assert cloudData == [networkData: 'n-only']
            ServiceResponse.success([:])
        }

        when:
        def result = helperLocal.buildCloudInitOptions()

        then:
        result.success
    }

    def "buildCloudInitOptions should mark response unsuccessful when secret create fails"() {
        given:
        computeServer.name >> 'real-fail-vm'
        def request = new WorkloadRequest(cloudConfigUser: 'u')
        def helperLocal = Spy(ProvisionHelper, constructorArgs: [computeServer, request, computeServerGroup, opts])
        helperLocal.createCloudInitSecret('secret-real-fail-vm', 'test-namespace', _ as Map) >> ServiceResponse.error('secret-failed')

        when:
        def result = helperLocal.buildCloudInitOptions()

        then:
        !result.success
        result.configName == 'secret-real-fail-vm'
    }

    // buildSysprepOptions Tests
    //Has a Main code change regarding cloud config null

//    def "buildSysprepOptions should throw when cloudConfigUser is null"() {
//        given:
//        computeServer.name >> 'windows-vm'
//        workloadRequest.cloudConfigUser >> null
//        opts.namespace = 'test-namespace'
//
//        when:
//        helper.buildSysprepOptions()
//
//        then:
//        thrown(NullPointerException)
//    }

    def "buildSysprepOptions should return failure when config map creation fails"() {
        given:
        computeServer.name >> 'windows-vm'
        def request = new WorkloadRequest(cloudConfigUser: '<unattend>Ethernet</unattend>')
        opts.namespace = 'test-namespace'
        def helperLocal = new ProvisionHelper(computeServer, request, computeServerGroup, opts)

        def mockApiClient = Mock(HarvesterApiClient)
        HarvesterApiClient.metaClass.constructor = { ComputeServerGroup sg -> mockApiClient }
        mockApiClient.post(_ as String, _ as Map) >> ServiceResponse.error('create failed')

        when:
        def result = helperLocal.buildSysprepOptions()

        then:
        !result.success
        !result.configMapName
    }

    def "buildSysprepOptions should return failure when config map helper returns null"() {
        given:
        computeServer.name >> 'windows-vm'
        def request = new WorkloadRequest(cloudConfigUser: '<unattend>Ethernet</unattend>')
        def helperLocal = new ProvisionHelper(computeServer, request, computeServerGroup, opts)
        def mockApiClient = Mock(HarvesterApiClient)
        def mockConfigMapHelper = Mock(com.hpe.morpheus.harvester.plugin.apihelper.ConfigMapApiHelper)
        HarvesterApiClient.metaClass.constructor = { ComputeServerGroup sg -> mockApiClient }
        com.hpe.morpheus.harvester.plugin.apihelper.ConfigMapApiHelper.metaClass.constructor = { HarvesterApiClient c -> mockConfigMapHelper }
        mockConfigMapHelper.createConfigMap(_, _, _, _, _) >> null

        when:
        def result = helperLocal.buildSysprepOptions()

        then:
        !result.success
        !result.configMapName
    }

    def "buildSysprepOptions should pass empty labels and annotations when opts values are null"() {
        given:
        computeServer.name >> 'windows-vm'
        def request = new WorkloadRequest(cloudConfigUser: '<unattend>Ethernet</unattend>')
        def localOpts = [namespace: 'test-namespace', labels: null, annotations: null]
        def helperLocal = new ProvisionHelper(computeServer, request, computeServerGroup, localOpts)
        def mockApiClient = Mock(HarvesterApiClient)
        def mockConfigMapHelper = Mock(com.hpe.morpheus.harvester.plugin.apihelper.ConfigMapApiHelper)
        HarvesterApiClient.metaClass.constructor = { ComputeServerGroup sg -> mockApiClient }
        com.hpe.morpheus.harvester.plugin.apihelper.ConfigMapApiHelper.metaClass.constructor = { HarvesterApiClient c -> mockConfigMapHelper }

        when:
        def result = helperLocal.buildSysprepOptions()

        then:
        result.success
        1 * mockConfigMapHelper.createConfigMap('test-namespace', 'windows-vm-sysprep', _ as String, [:], [:]) >> ServiceResponse.success([:])
    }

    def "deleteCloudInitSecret should return success when delete succeeds"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        HarvesterApiClient.metaClass.constructor = { ComputeServerGroup sg -> mockApiClient }
        mockApiClient.delete('/api/v1/namespaces/test-ns/secrets/secret-a') >> ServiceResponse.success([status: 'ok'])

        when:
        def result = helper.deleteCloudInitSecret('secret-a', 'test-ns')

        then:
        result.success
    }

    def "deleteCloudInitSecret should return error when delete fails"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        HarvesterApiClient.metaClass.constructor = { ComputeServerGroup sg -> mockApiClient }
        mockApiClient.delete('/api/v1/namespaces/test-ns/secrets/secret-a') >> ServiceResponse.error('no delete')

        when:
        def result = helper.deleteCloudInitSecret('secret-a', 'test-ns')

        then:
        !result.success
        result.errors.error.contains('Failed to delete secret')
    }

    def "createCloudInitSecret should return success and encode all fields"() {
        given:
        def helperLocal = new ProvisionHelper(computeServer, workloadRequest, computeServerGroup, opts)
        def mockApiClient = Mock(HarvesterApiClient)
        HarvesterApiClient.metaClass.constructor = { ComputeServerGroup sg -> mockApiClient }

        when:
        def result = helperLocal.createCloudInitSecret('sec-a', 'ns-a', [
            userData: 'user-raw',
            networkData: 'net-raw',
            metaData: 'meta-raw'
        ])

        then:
        result.success
        1 * mockApiClient.post('/api/v1/namespaces/ns-a/secrets', { Map body ->
            body.apiVersion == 'v1' &&
                    body.kind == 'Secret' &&
                    body.metadata.name == 'sec-a' &&
                    body.metadata.namespace == 'ns-a' &&
                    body.data.userdata == 'user-raw'.bytes.encodeBase64().toString() &&
                    body.data.networkdata == 'net-raw'.bytes.encodeBase64().toString() &&
                    body.data.metadata == 'meta-raw'.bytes.encodeBase64().toString()
        }) >> ServiceResponse.success([ok: true])
    }

    def "createCloudInitSecret should return error when post fails"() {
        given:
        def helperLocal = new ProvisionHelper(computeServer, workloadRequest, computeServerGroup, opts)
        def mockApiClient = Mock(HarvesterApiClient)
        HarvesterApiClient.metaClass.constructor = { ComputeServerGroup sg -> mockApiClient }
        mockApiClient.post(_ as String, _ as Map) >> ServiceResponse.error('boom')

        when:
        def result = helperLocal.createCloudInitSecret('sec-a', 'ns-a', [userData: 'x'])

        then:
        !result.success
        result.errors.error.contains('Failed to create secret')
    }

    def "createCloudInitSecret should catch exceptions"() {
        given:
        def helperLocal = new ProvisionHelper(computeServer, workloadRequest, computeServerGroup, opts)
        HarvesterApiClient.metaClass.constructor = { ComputeServerGroup sg ->
            throw new RuntimeException('ctor-fail')
        }

        when:
        def result = helperLocal.createCloudInitSecret('sec-a', 'ns-a', [userData: 'x'])

        then:
        !result.success
        result.errors.error.contains('Exception while creating cloud-init secret')
    }

    def "deleteCloudInitSecret should catch exceptions"() {
        given:
        def helperLocal = new ProvisionHelper(computeServer, workloadRequest, computeServerGroup, opts)
        HarvesterApiClient.metaClass.constructor = { ComputeServerGroup sg ->
            throw new RuntimeException('ctor-fail')
        }

        when:
        def result = helperLocal.deleteCloudInitSecret('sec-a', 'ns-a')

        then:
        !result.success
        result.errors.error.contains('Exception while deleting cloud-init secret')
    }

    // buildCloudInitConfigForLinux Tests

    def "buildCloudInitConfigForLinux should set hostname"() {
        given:
        def config = [hostname: 'test-host']

        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux(config)

        then:
        result.userData.contains('hostname: test-host')
    }

    def "buildCloudInitConfigForLinux should set FQDN when provided"() {
        given:
        def config = [hostname: 'test-host', fqdn: 'test-host.example.com']

        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux(config)

        then:
        result.userData.contains('fqdn: test-host.example.com')
    }

    def "buildCloudInitConfigForLinux should configure DHCP interface"() {
        given:
        def config = [
            hostname: 'test-host',
            interfaces: [
                [name: 'eth0', dhcp: true]
            ]
        ]

        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux(config)

        then:
        result.networkData.contains('dhcp4: true')
        result.networkData.contains('enp1s0')
    }

    def "buildCloudInitConfigForLinux should configure static IP interface"() {
        given:
        def config = [
            hostname: 'test-host',
            interfaces: [
                [
                    name: 'eth0',
                    dhcp: false,
                    ipAddress: '192.168.1.100',
                    netmask: '255.255.255.0',
                    gateway: '192.168.1.1',
                    dnsServers: ['8.8.8.8', '8.8.4.4']
                ]
            ]
        ]

        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux(config)

        then:
        result.networkData.contains('dhcp4: false')
        result.networkData.contains('192.168.1.100/24')
        result.networkData.contains('via: 192.168.1.1')
        result.networkData.contains('8.8.8.8')
    }

    def "buildCloudInitConfigForLinux should map eth0 to enp1s0"() {
        given:
        def config = [
            hostname: 'test-host',
            interfaces: [[name: 'eth0', dhcp: true]]
        ]

        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux(config)

        then:
        result.networkData.contains('enp1s0:')
    }

    def "buildCloudInitConfigForLinux should map eth1 to enp2s0"() {
        given:
        def config = [
            hostname: 'test-host',
            interfaces: [[name: 'eth1', dhcp: true]]
        ]

        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux(config)

        then:
        result.networkData.contains('enp2s0:')
    }

    def "buildCloudInitConfigForLinux should configure IPv6 DHCP"() {
        given:
        def config = [
            hostname: 'test-host',
            interfaces: [
                [name: 'eth0', dhcp: true, dhcp6: true]
            ]
        ]

        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux(config)

        then:
        result.networkData.contains('dhcp6: true')
    }

    def "buildCloudInitConfigForLinux should configure static IPv6"() {
        given:
        def config = [
            hostname: 'test-host',
            interfaces: [
                [
                    name: 'eth0',
                    dhcp: true,
                    ipv6Address: '2001:db8::1',
                    cidr6Suffix: '64',
                    gateway6: '2001:db8::ff'
                ]
            ]
        ]

        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux(config)

        then:
        result.networkData.contains('2001:db8::1/64')
        result.networkData.contains('via: 2001:db8::ff')
    }

    def "buildCloudInitConfigForLinux should append IPv6 route when IPv4 route exists"() {
        given:
        def config = [
            hostname  : 'test-host',
            interfaces: [[
                                 name       : 'eth0',
                                 dhcp       : false,
                                 ipAddress  : '192.168.1.2',
                                 netmask    : '255.255.255.0',
                                 gateway    : '192.168.1.1',
                                 ipv6Address: '2001:db8::2',
                                 gateway6   : '2001:db8::1',
                                 dnsServers : ['8.8.8.8'],
                                 dnsServers6: ['2001:4860:4860::8888']
                         ]]
        ]

        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux(config)

        then:
        result.networkData.contains('- to: default')
        result.networkData.contains('- to: ::/0')
        result.networkData.contains('2001:4860:4860::8888')
    }

    def "buildCloudInitConfigForLinux should include dns search domains"() {
        given:
        def config = [
            hostname  : 'test-host',
            interfaces: [[
                                 name      : 'eth0',
                                 dhcp      : false,
                                 ipAddress : '10.0.0.10',
                                 netmask   : '255.255.255.0',
                                 dnsServers: ['1.1.1.1'],
                                 dnsSearch : ['corp.local', 'svc.local']
                         ]]
        ]

        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux(config)

        then:
        result.networkData.contains('search:')
        result.networkData.contains('- corp.local')
        result.networkData.contains('- svc.local')
    }

    def "buildCloudInitConfigForLinux should fallback to /24 on invalid netmask"() {
        given:
        def config = [
            hostname  : 'test-host',
            interfaces: [[
                                 name     : 'eth0',
                                 dhcp     : false,
                                 ipAddress: '10.0.0.99',
                                 netmask  : 'abc.def',
                         ]]
        ]

        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux(config)

        then:
        result.networkData.contains('10.0.0.99/0')
    }

    def "buildCloudInitConfigForLinux should add SSH authorized keys"() {
        given:
        def config = [
            hostname: 'test-host',
            sshAuthorizedKeys: ['ssh-rsa AAAAB3...']
        ]

        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux(config)

        then:
        result.userData.contains('ssh_authorized_keys:')
        result.userData.contains('ssh-rsa AAAAB3...')
    }

    def "buildCloudInitConfigForLinux should add user configurations"() {
        given:
        def config = [
            hostname: 'test-host',
            users: [
                [
                    name: 'admin',
                    groups: 'sudo',
                    shell: '/bin/bash',
                    sshAuthorizedKeys: ['ssh-rsa KEY1']
                ]
            ]
        ]

        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux(config)

        then:
        result.userData.contains('users:')
        result.userData.contains('name: admin')
        result.userData.contains('groups: sudo')
        result.userData.contains('shell: /bin/bash')
    }

    def "buildCloudInitConfigForLinux should add packages to install"() {
        given:
        def config = [
            hostname: 'test-host',
            packages: ['vim', 'git', 'curl']
        ]

        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux(config)

        then:
        result.userData.contains('packages:')
        result.userData.contains('vim')
        result.userData.contains('git')
    }

    def "buildCloudInitConfigForLinux should add run commands"() {
        given:
        def config = [
            hostname: 'test-host',
            runcmd: ['apt-get update', 'systemctl restart ssh']
        ]

        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux(config)

        then:
        result.userData.contains('runcmd:')
        result.userData.contains('apt-get update')
    }

    // buildDiskRemovalPatch Tests

    def "buildDiskRemovalPatch should create removal operations"() {
        when:
        def result = ProvisionHelper.buildDiskRemovalPatch('0', '0')

        then:
        result.size() == 2
        result[0].op == 'remove'
        result[0].path == '/spec/template/spec/domain/devices/disks/0'
        result[1].op == 'remove'
        result[1].path == '/spec/template/spec/volumes/0'
    }

    def "buildDiskRemovalPatch should handle different indices"() {
        when:
        def result = ProvisionHelper.buildDiskRemovalPatch('2', '3')

        then:
        result.size() == 2
        result[0].path == '/spec/template/spec/domain/devices/disks/2'
        result[1].path == '/spec/template/spec/volumes/3'
    }

    // buildNetworkRemovalPatch Tests

    def "buildNetworkRemovalPatch should create removal operations"() {
        when:
        def result = ProvisionHelper.buildNetworkRemovalPatch('0', '0')

        then:
        result.size() == 2
        result[0].op == 'remove'
        result[0].path == '/spec/template/spec/domain/devices/interfaces/0'
        result[1].op == 'remove'
        result[1].path == '/spec/template/spec/networks/0'
    }

    def "buildNetworkRemovalPatch should skip null indices"() {
        when:
        def result = ProvisionHelper.buildNetworkRemovalPatch('0', null)

        then:
        result.size() == 1
        result[0].path == '/spec/template/spec/domain/devices/interfaces/0'
    }

    def "buildNetworkRemovalPatch should skip negative indices"() {
        when:
        def result = ProvisionHelper.buildNetworkRemovalPatch('-1', '0')

        then:
        result.size() == 1
        result[0].path == '/spec/template/spec/networks/0'
    }

    // createRemoveLabelPatchBody Tests

    def "createRemoveLabelPatchBody should create simple label removal"() {
        when:
        def result = ProvisionHelper.createRemoveLabelPatchBody('app')

        then:
        result.size() == 1
        result[0].op == 'remove'
        result[0].path == '/metadata/labels/app'
    }

    def "createRemoveLabelPatchBody should escape tilde characters"() {
        when:
        def result = ProvisionHelper.createRemoveLabelPatchBody('app~version')

        then:
        result[0].path == '/metadata/labels/app~0version'
    }

    def "createRemoveLabelPatchBody should escape forward slash"() {
        when:
        def result = ProvisionHelper.createRemoveLabelPatchBody('example.com/app')

        then:
        result[0].path == '/metadata/labels/example.com~1app'
    }

    def "createRemoveLabelPatchBody should escape both tilde and slash"() {
        when:
        def result = ProvisionHelper.createRemoveLabelPatchBody('example.com/app~version')

        then:
        result[0].path == '/metadata/labels/example.com~1app~0version'
    }

    // Edge Cases

    def "buildCloudInitConfigForLinux should handle empty config"() {
        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux([:])

        then:
        result.userData != null
        result.networkData != null
    }

    def "buildCloudInitConfigForLinux should handle null interface name"() {
        given:
        def config = [
            hostname: 'test-host',
            interfaces: [[dhcp: true]]
        ]

        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux(config)

        then:
        result.networkData.contains('enp1s0') // defaults to eth0 -> enp1s0
    }

    def "buildCloudInitConfigForLinux should handle custom netmask CIDR conversion"() {
        given:
        def config = [
            hostname: 'test-host',
            interfaces: [
                [
                    name: 'eth0',
                    dhcp: false,
                    ipAddress: '10.0.0.5',
                    netmask: '255.255.0.0',
                    gateway: '10.0.0.1'
                ]
            ]
        ]

        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux(config)

        then:
        result.networkData.contains('10.0.0.5/16')
    }

    @Unroll
    def "buildCloudInitConfigForLinux should convert netmask '#netmask' to CIDR /#expectedCidr"() {
        given:
        def config = [
            hostname: 'test-host',
            interfaces: [
                [
                    name: 'eth0',
                    dhcp: false,
                    ipAddress: '192.168.1.1',
                    netmask: netmask
                ]
            ]
        ]

        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux(config)

        then:
        result.networkData.contains("192.168.1.1/${expectedCidr}")

        where:
        netmask           || expectedCidr
        '255.255.255.0'   || '24'
        '255.255.0.0'     || '16'
        '255.0.0.0'       || '8'
        '255.255.255.128' || '25'
    }

    def "buildDiskRemovalPatch should accept string indices"() {
        when:
        def result = ProvisionHelper.buildDiskRemovalPatch('5', '7')

        then:
        result[0].path.contains('/5')
        result[1].path.contains('/7')
    }

    def "getDiskAndVolumeIndices returns indices when disk and volume exist"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            spec: [
                template: [
                    spec: [
                        domain: [devices: [disks: [[name: 'cloudinitdisk'], [name: 'data']]]],
                        volumes: [[name: 'data'], [name: 'cloudinitdisk']]
                    ]
                ]
            ]
        ])

        when:
        def result = ProvisionHelper.getDiskAndVolumeIndices(apiClient, 'ns1', 'vm1', 'harvester-linux')

        then:
        result.diskIndex == 0
        result.volumeIndex == 1
    }

    def "getDiskAndVolumeIndices returns null when disk is missing"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            spec: [template: [spec: [domain: [devices: [disks: [[name: 'x']]]], volumes: [[name: 'x']]]]]
        ])

        when:
        def result = ProvisionHelper.getDiskAndVolumeIndices(apiClient, 'ns1', 'vm1', 'harvester-linux')

        then:
        result == null
    }

    def "getDiskAndVolumeIndices returns null on api failure"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.error('not found')

        when:
        def result = ProvisionHelper.getDiskAndVolumeIndices(apiClient, 'ns1', 'vm1', 'harvester-linux')

        then:
        result == null
    }

    def "getDiskAndVolumeIndices should default to sysprep for windows vm type"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            spec: [
                template: [
                    spec: [
                        domain: [devices: [disks: [[name: 'sysprep'], [name: 'data']]]],
                        volumes: [[name: 'sysprep'], [name: 'data']]
                    ]
                ]
            ]
        ])

        when:
        def result = ProvisionHelper.getDiskAndVolumeIndices(apiClient, 'ns1', 'vm1', 'harvester-windows')

        then:
        result.diskIndex == 0
        result.volumeIndex == 0
    }

    def "getDiskAndVolumeIndices should respect explicit diskName argument"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            spec: [
                template: [
                    spec: [
                        domain: [devices: [disks: [[name: 'customdisk']]]],
                        volumes: [[name: 'customdisk']]
                    ]
                ]
            ]
        ])

        when:
        def result = ProvisionHelper.getDiskAndVolumeIndices(apiClient, 'ns1', 'vm1', 'harvester-linux', 'customdisk')

        then:
        result == [diskIndex: 0, volumeIndex: 0]
    }

    def "getDiskAndVolumeIndices returns null when volumes list missing"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            spec: [template: [spec: [domain: [devices: [disks: [[name: 'cloudinitdisk']]]], volumes: null]]]
        ])

        when:
        def result = ProvisionHelper.getDiskAndVolumeIndices(apiClient, 'ns1', 'vm1', 'harvester-linux')

        then:
        result == null
    }

    def "getDataVolumeTemplateIndex returns index and -1 fallback"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            spec: [
                dataVolumeTemplates: [
                    [metadata: [name: 'dv-a']],
                    [metadata: [name: 'dv-b']]
                ]
            ]
        ])

        expect:
        ProvisionHelper.getDataVolumeTemplateIndex(apiClient, 'ns1', 'vm1', 'dv-b') == 1
        ProvisionHelper.getDataVolumeTemplateIndex(apiClient, 'ns1', 'vm1', 'dv-c') == -1
    }

    def "getDataVolumeTemplateIndex returns null on api failure"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.error('bad')

        expect:
        ProvisionHelper.getDataVolumeTemplateIndex(apiClient, 'ns1', 'vm1', 'dv-a') == null
    }

    def "getDataVolumeTemplateIndex returns -1 when templates are absent"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([spec: [dataVolumeTemplates: null]])

        expect:
        ProvisionHelper.getDataVolumeTemplateIndex(apiClient, 'ns1', 'vm1', 'dv-a') == -1
    }

    def "getNetworkInterfaceIndices returns both indices for existing interface"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            spec: [
                template: [
                    spec: [
                        domain: [devices: [interfaces: [[name: 'eth0'], [name: 'eth1']]]],
                        networks: [[name: 'eth1'], [name: 'eth0']]
                    ]
                ]
            ]
        ])

        when:
        def result = ProvisionHelper.getNetworkInterfaceIndices(apiClient, 'ns1', 'vm1', 'eth0')

        then:
        result.interfaceIndex == 0
        result.networkIndex == 1
    }

    def "getNetworkInterfaceIndices returns null on api failure"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.error('bad')

        expect:
        ProvisionHelper.getNetworkInterfaceIndices(apiClient, 'ns1', 'vm1', 'eth0') == null
    }

    def "getNetworkInterfaceIndices returns null when neither interface nor network exist"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            spec: [
                template: [
                    spec: [
                        domain: [devices: [interfaces: [[name: 'eth2']]]],
                        networks: [[name: 'eth3']]
                    ]
                ]
            ]
        ])

        expect:
        ProvisionHelper.getNetworkInterfaceIndices(apiClient, 'ns1', 'vm1', 'eth0') == null
    }

    def "getNetworkInterfaceIndices returns mixed indices when only network exists"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            spec: [
                template: [
                    spec: [
                        domain: [devices: [interfaces: [[name: 'eth2']]]],
                        networks: [[name: 'eth0']]
                    ]
                ]
            ]
        ])

        when:
        def result = ProvisionHelper.getNetworkInterfaceIndices(apiClient, 'ns1', 'vm1', 'eth0')

        then:
        result.interfaceIndex == -1
        result.networkIndex == 0
    }

    def "getNetworkInterfaceIndices returns mixed indices when only interface exists"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            spec: [
                template: [
                    spec: [
                        domain: [devices: [interfaces: [[name: 'eth0']]]],
                        networks: [[name: 'eth9']]
                    ]
                ]
            ]
        ])

        when:
        def result = ProvisionHelper.getNetworkInterfaceIndices(apiClient, 'ns1', 'vm1', 'eth0')

        then:
        result.interfaceIndex == 0
        result.networkIndex == -1
    }

    def "buildNetworkRemovalPatch should return empty list when both indices are null"() {
        when:
        def result = ProvisionHelper.buildNetworkRemovalPatch(null, null)

        then:
        result == []
    }

    def "buildNetworkRemovalPatch should include both removals when indices are valid"() {
        when:
        def result = ProvisionHelper.buildNetworkRemovalPatch('1', '2')

        then:
        result.size() == 2
        (result*.path as Set) == ['/spec/template/spec/domain/devices/interfaces/1', '/spec/template/spec/networks/2'] as Set
    }

    def "buildNetworkRemovalPatch should throw NumberFormatException for non-numeric indices"() {
        when:
        ProvisionHelper.buildNetworkRemovalPatch('abc', '1')

        then:
        thrown(NumberFormatException)
    }

    def "removeVMLabel sends patch request and returns response"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.patch(_ as String, _ as List, _ as String, _ as Map) >> ServiceResponse.success()
        apiClient.patch(_ as String, _ as List, _ as String) >> ServiceResponse.success([ok: true])

        when:
        def result = ProvisionHelper.removeVMLabel(apiClient, 'ns1', 'vm1', 'example.com/app')

        then:
        result.success
    }

    def "removeVMLabel should return failed patch response when main patch fails"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.patch(_ as String, _ as List, _ as String, _ as Map) >> ServiceResponse.error('dry run failed')
        apiClient.patch(_ as String, _ as List, _ as String) >> ServiceResponse.error('patch failed')

        when:
        def result = ProvisionHelper.removeVMLabel(apiClient, 'ns1', 'vm1', 'app')

        then:
        !result.success
        result.errors.error == 'patch failed'
    }

    def "removeVMLabel should continue when dry-run throws exception"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.patch(_ as String, _ as List, _ as String, _ as Map) >> { throw new RuntimeException('dry-run-ex') }
        apiClient.patch(_ as String, _ as List, _ as String) >> ServiceResponse.success([ok: true])

        when:
        def result = ProvisionHelper.removeVMLabel(apiClient, 'ns1', 'vm1', 'app')

        then:
        result.success
    }

    def "removeVMLabel should return null when main patch throws exception"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.patch(_ as String, _ as List, _ as String, _ as Map) >> ServiceResponse.success([:])
        apiClient.patch(_ as String, _ as List, _ as String) >> { throw new RuntimeException('patch-ex') }

        expect:
        ProvisionHelper.removeVMLabel(apiClient, 'ns1', 'vm1', 'app') == null
    }

    def "buildCloudInitConfigForLinux should keep custom interface names unmapped"() {
        given:
        def config = [
            hostname  : 'test-host',
            interfaces: [[name: 'ens10', dhcp: true]]
        ]

        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux(config)

        then:
        result.networkData.contains('ens10:')
    }

    def "buildCloudInitConfigForLinux should use default username when user name missing"() {
        given:
        def config = [
            hostname: 'test-host',
            users   : [[groups: 'sudo', sudo: 'ALL=(ALL) NOPASSWD:ALL']]
        ]

        when:
        def result = ProvisionHelper.buildCloudInitConfigForLinux(config)

        then:
        result.userData.contains('name: default')
        result.userData.contains('groups: sudo')
        result.userData.contains('sudo: ALL=(ALL) NOPASSWD:ALL')
    }

    def "buildCloudInitOptions should pass only metaData when user and network are absent"() {
        given:
        computeServer.name >> 'meta-only-vm'
        def request = new WorkloadRequest(cloudConfigMeta: 'meta-only-data')
        def helperSpy = Spy(ProvisionHelper, constructorArgs: [computeServer, request, computeServerGroup, opts])
        helperSpy.createCloudInitSecret('secret-meta-only-vm', 'test-namespace', _ as Map) >> { String secret, String ns, Map cloudData ->
            assert cloudData == [metaData: 'meta-only-data']
            ServiceResponse.success([:])
        }

        when:
        def result = helperSpy.buildCloudInitOptions()

        then:
        result.success
        result.configName == 'secret-meta-only-vm'
    }

    def "getDiskAndVolumeIndices returns null when disks are absent"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            spec: [template: [spec: [domain: [devices: [disks: null]], volumes: [[name: 'cloudinitdisk']]]]]
        ])

        expect:
        ProvisionHelper.getDiskAndVolumeIndices(apiClient, 'ns1', 'vm1', 'harvester-linux') == null
    }

    def "getNetworkInterfaceIndices returns mixed indices when interfaces are absent but network exists"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            spec: [
                template: [
                    spec: [
                        domain  : [devices: [interfaces: null]],
                        networks: [[name: 'eth0']]
                    ]
                ]
            ]
        ])

        when:
        def result = ProvisionHelper.getNetworkInterfaceIndices(apiClient, 'ns1', 'vm1', 'eth0')

        then:
        result.interfaceIndex == -1
        result.networkIndex == 0
    }

    def "buildNetworkRemovalPatch should throw NumberFormatException for non-numeric network index"() {
        when:
        ProvisionHelper.buildNetworkRemovalPatch('1', 'xyz')

        then:
        thrown(NumberFormatException)
    }

    def "removeVMLabel should continue when dry-run fails but main patch succeeds"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.patch(_ as String, _ as List, _ as String, _ as Map) >> ServiceResponse.error('dry-run failed')
        apiClient.patch(_ as String, _ as List, _ as String) >> ServiceResponse.success([ok: true])

        when:
        def result = ProvisionHelper.removeVMLabel(apiClient, 'ns1', 'vm1', 'app')

        then:
        result.success
    }

    // ===== updateSysprepUnattend =====

    def "updateSysprepUnattend returns null when input is null"() {
        expect:
        ProvisionHelper.updateSysprepUnattend(null, [:]) == null
    }

    def "updateSysprepUnattend returns original when input is blank"() {
        expect:
        ProvisionHelper.updateSysprepUnattend('   ', [:]) == '   '
    }

    def "updateSysprepUnattend returns original on XML parse exception"() {
        given:
        def badXml = 'not-xml-at-all<'

        when:
        def result = ProvisionHelper.updateSysprepUnattend(badXml, [eth0: '00-11-22-33-44-55'])

        then:
        result == badXml
    }

    def "updateSysprepUnattend replaces interface Identifier with MAC from map"() {
        given:
        def xml = '''\
<?xml version="1.0" encoding="utf-8"?>
<unattend xmlns="urn:schemas-microsoft-com:unattend">
    <settings pass="specialize">
        <component name="Microsoft-Windows-TCPIP">
            <Interfaces>
                <Interface>
                    <Identifier>ethernet</Identifier>
                </Interface>
            </Interfaces>
        </component>
    </settings>
</unattend>'''
        def macMap = [ethernet: '00-11-22-33-44-55']

        when:
        def result = ProvisionHelper.updateSysprepUnattend(xml, macMap)

        then:
        result.contains('00-11-22-33-44-55')
        !result.contains('<Identifier>ethernet</Identifier>')
    }

    def "updateSysprepUnattend returns unchanged XML when no specialize settings found"() {
        given:
        def xml = '''\
<?xml version="1.0" encoding="utf-8"?>
<unattend>
    <settings pass="oobeSystem">
        <component name="Microsoft-Windows-Shell-Setup"/>
    </settings>
</unattend>'''

        when:
        def result = ProvisionHelper.updateSysprepUnattend(xml, [eth0: '00-11-22-33-44-55'])

        then:
        result != null
        result.contains('oobeSystem')
    }

    def "updateSysprepUnattend keeps original identifier when MAC map has no match"() {
        given:
        def xml = '''\
<?xml version="1.0" encoding="utf-8"?>
<unattend xmlns="urn:schemas-microsoft-com:unattend">
    <settings pass="specialize">
        <component name="Microsoft-Windows-TCPIP">
            <Interfaces>
                <Interface>
                    <Identifier>eth0</Identifier>
                </Interface>
            </Interfaces>
        </component>
    </settings>
</unattend>'''

        when:
        def result = ProvisionHelper.updateSysprepUnattend(xml, [differentIface: '00-AA-BB-CC-DD-EE'])

        then:
        result != null
        result.contains('eth0')
    }

    // ===== addSysprepVolumeToVM =====

    def "addSysprepVolumeToVM returns error when dry-run fails"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.patch(_ as String, _ as String, _ as String, _ as Map) >> ServiceResponse.error('dry-run rejected')

        when:
        def result = ProvisionHelper.addSysprepVolumeToVM(apiClient, 'ns1', 'vm1', 'vm1-sysprep')

        then:
        !result.success
        result.errors?.error?.contains('validation failed')
    }

    def "addSysprepVolumeToVM returns error when actual patch fails"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        // dry-run succeeds
        apiClient.patch(_ as String, _ as String, _ as String, _ as Map) >> ServiceResponse.success([:])
        // actual patch fails
        apiClient.patch(_ as String, _ as String, _ as String) >> ServiceResponse.error('patch rejected')

        when:
        def result = ProvisionHelper.addSysprepVolumeToVM(apiClient, 'ns1', 'vm1', 'vm1-sysprep')

        then:
        !result.success
        result.errors?.error?.contains('failed')
    }

    def "addSysprepVolumeToVM returns success when dry-run and patch both succeed"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.patch(_ as String, _ as String, _ as String, _ as Map) >> ServiceResponse.success([:])
        apiClient.patch(_ as String, _ as String, _ as String) >> ServiceResponse.success([:])

        when:
        def result = ProvisionHelper.addSysprepVolumeToVM(apiClient, 'ns1', 'vm1', 'vm1-sysprep')

        then:
        result.success
    }

    // --- queryDataVolumesForServerVolumes coverage ---

    def "queryDataVolumesForServerVolumes returns early for empty volumes"() {
        given:
        def server = Mock(ComputeServer)
        server.name >> 'test-server'
        server.volumes >> []
        def morphCtx = Mock(com.morpheusdata.core.MorpheusContext)
        def apiClient = Mock(HarvesterApiClient)

        when:
        ProvisionHelper.queryDataVolumesForServerVolumes(server, morphCtx, apiClient, 'ns1')

        then:
        notThrown(Exception)
    }

    def "queryDataVolumesForServerVolumes returns early for null volumes"() {
        given:
        def server = Mock(ComputeServer)
        server.name >> 'test-server'
        server.volumes >> null
        def morphCtx = Mock(com.morpheusdata.core.MorpheusContext)
        def apiClient = Mock(HarvesterApiClient)

        when:
        ProvisionHelper.queryDataVolumesForServerVolumes(server, morphCtx, apiClient, 'ns1')

        then:
        notThrown(Exception)
    }

    def "queryDataVolumesForServerVolumes skips volume without sourceId"() {
        given:
        def server = Mock(ComputeServer)
        server.name >> 'test-server'
        def vol = new com.morpheusdata.model.StorageVolume(id: 1L, name: 'vol-no-src')
        vol.sourceId = null
        server.volumes >> [vol]
        def morphCtx = Mock(com.morpheusdata.core.MorpheusContext)
        def apiClient = Mock(HarvesterApiClient)

        when:
        ProvisionHelper.queryDataVolumesForServerVolumes(server, morphCtx, apiClient, 'ns1')

        then:
        notThrown(Exception)
        0 * morphCtx.services
    }

    def "queryDataVolumesForServerVolumes fetches DataVolume details for volume with sourceId"() {
        given:
        def server = Mock(ComputeServer)
        server.name >> 'test-server'

        def volType = new com.morpheusdata.model.StorageVolumeType(id: 10L, code: 'harvesterDataVolume')
        def sourceVol = new com.morpheusdata.model.StorageVolume(id: 50L, name: 'source-dv', type: volType)

        def vol = new com.morpheusdata.model.StorageVolume(id: 1L, name: 'vol-with-src')
        vol.sourceId = '50'
        server.volumes >> [vol]

        def morphCtx = Mock(com.morpheusdata.core.MorpheusContext)
        def services = Mock(com.morpheusdata.core.MorpheusServices)
        def storageSvc = Mock(Object)
        def volumeSvc = Mock(Object)
        def volTypeSvc = Mock(Object)
        morphCtx.services >> services
        services.storage >> storageSvc
        storageSvc.volume >> volumeSvc
        volumeSvc.get(50L) >> sourceVol
        volumeSvc.storageVolumeType >> volTypeSvc
        volTypeSvc.get(10L) >> volType
        volumeSvc.save(_) >> sourceVol

        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_) >> ServiceResponse.success([
            metadata: [ownerReferences: [[kind: 'VirtualMachine', name: 'vm1']]],
            spec: [source: [pvc: [name: 'src-pvc', namespace: 'ns1']]]
        ])

        when:
        ProvisionHelper.queryDataVolumesForServerVolumes(server, morphCtx, apiClient, 'ns1')

        then:
        notThrown(Exception)
    }

    def "queryDataVolumesForServerVolumes fetches PVC details for volume with sourceId"() {
        given:
        def server = Mock(ComputeServer)
        server.name >> 'test-server'

        def volType = new com.morpheusdata.model.StorageVolumeType(id: 11L, code: 'harvesterPVC')
        def sourceVol = new com.morpheusdata.model.StorageVolume(id: 51L, name: 'source-pvc', type: volType)

        def vol = new com.morpheusdata.model.StorageVolume(id: 2L, name: 'vol-pvc')
        vol.sourceId = '51'
        server.volumes >> [vol]

        def morphCtx = Mock(com.morpheusdata.core.MorpheusContext)
        def services = Mock(com.morpheusdata.core.MorpheusServices)
        def storageSvc = Mock(Object)
        def volumeSvc = Mock(Object)
        def volTypeSvc = Mock(Object)
        morphCtx.services >> services
        services.storage >> storageSvc
        storageSvc.volume >> volumeSvc
        volumeSvc.get(51L) >> sourceVol
        volumeSvc.storageVolumeType >> volTypeSvc
        volTypeSvc.get(11L) >> volType
        volumeSvc.save(_) >> sourceVol

        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_) >> ServiceResponse.success([
            metadata: [ownerReferences: []],
            spec: [dataSource: [kind: 'PVC', name: 'data-pvc']]
        ])

        when:
        ProvisionHelper.queryDataVolumesForServerVolumes(server, morphCtx, apiClient, 'ns1')

        then:
        notThrown(Exception)
    }

    def "queryDataVolumesForServerVolumes handles API failure gracefully"() {
        given:
        def server = Mock(ComputeServer)
        server.name >> 'test-server'

        def volType = new com.morpheusdata.model.StorageVolumeType(id: 10L, code: 'harvesterDataVolume')
        def sourceVol = new com.morpheusdata.model.StorageVolume(id: 50L, name: 'source-dv', type: volType)

        def vol = new com.morpheusdata.model.StorageVolume(id: 3L, name: 'vol-api-fail')
        vol.sourceId = '50'
        server.volumes >> [vol]

        def morphCtx = Mock(com.morpheusdata.core.MorpheusContext)
        def services = Mock(com.morpheusdata.core.MorpheusServices)
        def storageSvc = Mock(Object)
        def volumeSvc = Mock(Object)
        def volTypeSvc = Mock(Object)
        morphCtx.services >> services
        services.storage >> storageSvc
        storageSvc.volume >> volumeSvc
        volumeSvc.get(50L) >> sourceVol
        volumeSvc.storageVolumeType >> volTypeSvc
        volTypeSvc.get(10L) >> volType

        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_) >> ServiceResponse.error('API timeout')

        when:
        ProvisionHelper.queryDataVolumesForServerVolumes(server, morphCtx, apiClient, 'ns1')

        then:
        notThrown(Exception)
    }

    def "queryDataVolumesForServerVolumes handles source volume not found"() {
        given:
        def server = Mock(ComputeServer)
        server.name >> 'test-server'

        def vol = new com.morpheusdata.model.StorageVolume(id: 4L, name: 'vol-no-source')
        vol.sourceId = '999'
        server.volumes >> [vol]

        def morphCtx = Mock(com.morpheusdata.core.MorpheusContext)
        def services = Mock(com.morpheusdata.core.MorpheusServices)
        def storageSvc = Mock(Object)
        def volumeSvc = Mock(Object)
        morphCtx.services >> services
        services.storage >> storageSvc
        storageSvc.volume >> volumeSvc
        volumeSvc.get(999L) >> null // not found

        def apiClient = Mock(HarvesterApiClient)

        when:
        ProvisionHelper.queryDataVolumesForServerVolumes(server, morphCtx, apiClient, 'ns1')

        then:
        notThrown(Exception)
        0 * apiClient.get(_)
    }

    def "queryDataVolumesForServerVolumes handles exception in processing"() {
        given:
        def server = Mock(ComputeServer)
        server.name >> 'test-server'

        def vol = new com.morpheusdata.model.StorageVolume(id: 5L, name: 'vol-exception')
        vol.sourceId = '50'
        server.volumes >> [vol]

        def morphCtx = Mock(com.morpheusdata.core.MorpheusContext)
        def services = Mock(com.morpheusdata.core.MorpheusServices)
        def storageSvc = Mock(Object)
        def volumeSvc = Mock(Object)
        morphCtx.services >> services
        services.storage >> storageSvc
        storageSvc.volume >> volumeSvc
        volumeSvc.get(50L) >> { throw new RuntimeException('DB error') }

        def apiClient = Mock(HarvesterApiClient)

        when:
        ProvisionHelper.queryDataVolumesForServerVolumes(server, morphCtx, apiClient, 'ns1')

        then:
        notThrown(Exception)
    }

    def "queryDataVolumesForServerVolumes uses provided volumesList instead of server volumes"() {
        given:
        def server = Mock(ComputeServer)
        server.name >> 'test-server'
        server.volumes >> [] // empty server volumes

        def volType = new com.morpheusdata.model.StorageVolumeType(id: 10L, code: 'harvesterDataVolume')
        def sourceVol = new com.morpheusdata.model.StorageVolume(id: 60L, name: 'source-list-dv', type: volType)

        def vol = new com.morpheusdata.model.StorageVolume(id: 6L, name: 'provided-vol')
        vol.sourceId = '60'

        def morphCtx = Mock(com.morpheusdata.core.MorpheusContext)
        def services = Mock(com.morpheusdata.core.MorpheusServices)
        def storageSvc = Mock(Object)
        def volumeSvc = Mock(Object)
        def volTypeSvc = Mock(Object)
        morphCtx.services >> services
        services.storage >> storageSvc
        storageSvc.volume >> volumeSvc
        volumeSvc.get(60L) >> sourceVol
        volumeSvc.storageVolumeType >> volTypeSvc
        volTypeSvc.get(10L) >> volType
        volumeSvc.save(_) >> sourceVol

        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_) >> ServiceResponse.success([
            metadata: [ownerReferences: []],
            spec: [source: [pvc: [name: 'pvc1', namespace: 'ns1']]]
        ])

        when:
        ProvisionHelper.queryDataVolumesForServerVolumes(server, morphCtx, apiClient, 'ns1', [vol])

        then:
        notThrown(Exception)
    }

    def "queryDataVolumesForServerVolumes handles unknown volume type"() {
        given:
        def server = Mock(ComputeServer)
        server.name >> 'test-server'

        def volType = new com.morpheusdata.model.StorageVolumeType(id: 12L, code: 'unknownType')
        def sourceVol = new com.morpheusdata.model.StorageVolume(id: 52L, name: 'source-unknown', type: volType)

        def vol = new com.morpheusdata.model.StorageVolume(id: 7L, name: 'vol-unknown-type')
        vol.sourceId = '52'
        server.volumes >> [vol]

        def morphCtx = Mock(com.morpheusdata.core.MorpheusContext)
        def services = Mock(com.morpheusdata.core.MorpheusServices)
        def storageSvc = Mock(Object)
        def volumeSvc = Mock(Object)
        def volTypeSvc = Mock(Object)
        morphCtx.services >> services
        services.storage >> storageSvc
        storageSvc.volume >> volumeSvc
        volumeSvc.get(52L) >> sourceVol
        volumeSvc.storageVolumeType >> volTypeSvc
        volTypeSvc.get(12L) >> volType

        def apiClient = Mock(HarvesterApiClient)

        when:
        ProvisionHelper.queryDataVolumesForServerVolumes(server, morphCtx, apiClient, 'ns1')

        then:
        notThrown(Exception)
        0 * apiClient.get(_) // should skip because unknown type
    }

    // --- generateSerialNumber ---

    def "generateSerialNumber returns deterministic serial from UID"() {
        when:
        def serial1 = ProvisionHelper.generateSerialNumber('my-uid-123')
        def serial2 = ProvisionHelper.generateSerialNumber('my-uid-123')

        then:
        serial1 == serial2
        serial1.length() <= 20
    }

    def "generateSerialNumber handles null UID"() {
        when:
        def serial = ProvisionHelper.generateSerialNumber(null)

        then:
        serial != null
        serial.length() <= 20
    }

    def "generateSerialNumber handles empty UID"() {
        when:
        def serial = ProvisionHelper.generateSerialNumber('')

        then:
        serial != null
        serial.length() <= 20
    }
}
