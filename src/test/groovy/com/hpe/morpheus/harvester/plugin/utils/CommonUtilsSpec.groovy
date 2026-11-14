package com.hpe.morpheus.harvester.plugin.utils

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.NetAddress
import spock.lang.Specification
import spock.lang.Unroll

import java.net.ServerSocket

class CommonUtilsSpec extends Specification {

    @Unroll
    def "getMorpheusOsTypeCode maps known Harvester OS types correctly - #harvesterOsType"() {
        expect:
        CommonUtils.getMorpheusOsTypeCode(harvesterOsType) == expectedCode

        where:
        harvesterOsType                 | expectedCode
        'centos.stream10'               | 'cent'
        'centos.stream10.desktop'       | 'cent'
        'centos-stream9'                | 'cent'
        'centos.stream9.desktop'        | 'cent'
        'centos.stream9.dpdk'           | 'cent'
        'fedora'                        | 'fedora'
        'rhel7'                         | 'redhat.7'
        'rhel8'                         | 'redhat.8'
        'rhel9'                         | 'redhat'
        'windows10'                     | 'windows.10'
        'windows11'                     | 'windows.11'
        'windows2k16'                   | 'windows.server.2016'
        'windows2016'                   | 'windows.server.2016'
        'windows2k19'                   | 'windows.server.2019'
        'windows2019'                   | 'windows.server.2019'
        'windows2k22'                   | 'windows.server.2022'
        'windows2022'                   | 'windows.server.2022'
    }

    @Unroll
    def "getMorpheusOsTypeCode falls back to generic codes for unknown OS types containing - #keyword"() {
        expect:
        CommonUtils.getMorpheusOsTypeCode(harvesterOsType) == expectedCode

        where:
        harvesterOsType           | expectedCode | keyword
        'WindowsXP'               | 'windows'    | 'windows'
        'WINDOWS_SERVER_2025'     | 'windows'    | 'windows'
        'rhel-custom-7.5'         | 'redhat'     | 'rhel'
        'RHEL_ATOMIC'             | 'redhat'     | 'rhel'
        'centos7-minimal'         | 'cent'       | 'centos'
        'CentOS-Stream-8'         | 'cent'       | 'centos'
        'ubuntu-20.04-lts'        | 'ubuntu'     | 'ubuntu'
        'Ubuntu22.04'             | 'ubuntu'     | 'ubuntu'
        'debian-10-buster'        | 'debian'     | 'debian'
        'Debian11'                | 'debian'     | 'debian'
        'opensuse-leap-15.4'      | 'suse'       | 'suse/opensuse'
        'suse-linux'              | 'suse'       | 'suse/opensuse'
        'fedora-coreos-38'        | 'fedora'     | 'fedora'
        'Fedora-Workstation'      | 'fedora'     | 'fedora'
        'macos-ventura'           | 'mac'        | 'mac'
        'darwin-arm64'            | 'mac'        | 'darwin'
    }

    def "getMorpheusOsTypeCode returns other.64 for completely unknown OS types"() {
        expect:
        CommonUtils.getMorpheusOsTypeCode('unknownOS') == 'other.64'
        CommonUtils.getMorpheusOsTypeCode('FreeBSD') == 'other.64'
        CommonUtils.getMorpheusOsTypeCode('ArchLinux') == 'other.64'
        CommonUtils.getMorpheusOsTypeCode('Alpine') == 'other.64'
    }

    def "getMorpheusOsTypeCode handles empty strings"() {
        expect:
        CommonUtils.getMorpheusOsTypeCode('') == 'other.64'
    }

    def "getNetworkTypeCode returns Harvester network type for all inputs"() {
        expect:
        CommonUtils.getNetworkTypeCode('any-type') == HarvesterConstants.HARVESTER_NETWORK_TYPE
        CommonUtils.getNetworkTypeCode(null) == HarvesterConstants.HARVESTER_NETWORK_TYPE
        CommonUtils.getNetworkTypeCode('') == HarvesterConstants.HARVESTER_NETWORK_TYPE
    }

    @Unroll
    def "getInterfaceTypeCode returns correct code for #interfaceModel"() {
        expect:
        CommonUtils.getInterfaceTypeCode(interfaceModel) == expectedCode

        where:
        interfaceModel | expectedCode
        'e1000'        | HarvesterConstants.E1000_INTERFACE_TYPE
        'e1000e'       | HarvesterConstants.E1000_INTERFACE_TYPE
        'E1000'        | HarvesterConstants.E1000_INTERFACE_TYPE
        'E1000E'       | HarvesterConstants.E1000_INTERFACE_TYPE
        'virtio'       | HarvesterConstants.VIRTIO_INTERFACE_TYPE
        'rtl8139'      | HarvesterConstants.VIRTIO_INTERFACE_TYPE
        null           | HarvesterConstants.VIRTIO_INTERFACE_TYPE
        ''             | HarvesterConstants.VIRTIO_INTERFACE_TYPE
    }

    @Unroll
    def "getIpType correctly identifies IPv4 addresses - #ipAddress"() {
        expect:
        CommonUtils.getIpType(ipAddress) == NetAddress.AddressType.IPV4

        where:
        ipAddress << [
            '192.168.1.1',
            '10.0.0.1',
            '172.16.0.1',
            '8.8.8.8',
            '127.0.0.1',
            '0.0.0.0',
            '255.255.255.255'
        ]
    }

    @Unroll
    def "getIpType correctly identifies IPv6 addresses - #ipAddress"() {
        expect:
        CommonUtils.getIpType(ipAddress) == NetAddress.AddressType.IPV6

        where:
        ipAddress << [
            '::1',
            '2001:db8::1',
            'fe80::1',
            '2001:0db8:85a3:0000:0000:8a2e:0370:7334'
        ]
    }

    def "getIpType returns IPV4 for invalid IP addresses as default"() {
        expect:
        CommonUtils.getIpType('invalid-ip') == NetAddress.AddressType.IPV4
        CommonUtils.getIpType('') == NetAddress.AddressType.IPV4
        CommonUtils.getIpType('not.an.ip.address') == NetAddress.AddressType.IPV4
    }

    def "param creates correct parameter map"() {
        when:
        def result = CommonUtils.param('testKey', 'testValue')

        then:
        result.key == 'testKey'
        result.value == 'testValue'
        result.operator == '='
    }

    def "param with custom operator creates correct parameter map"() {
        when:
        def result = CommonUtils.param('testKey', 'testValue', '!=')

        then:
        result.key == 'testKey'
        result.value == 'testValue'
        result.operator == '!='
    }

    def "param handles various value types"() {
        expect:
        CommonUtils.param('key', value).value == value

        where:
        value << [
            'string',
            123,
            123L,
            true,
            false,
            null,
            ['list', 'items'],
            [map: 'value']
        ]
    }

    def "getServerGroupId returns ID from resourcePool.serverGroup when available"() {
        given:
        ComputeServer mockServer = Mock(ComputeServer)
        mockServer.resourcePool >> [serverGroup: [id: 456L]]

        when:
        Long result = CommonUtils.getServerGroupId(mockServer, null)

        then:
        result == 456L
    }

    def "getServerGroupId fetches server when resourcePool.serverGroup is null"() {
        given:
        ComputeServer mockServer = Mock(ComputeServer)
        ComputeServer fetchedServer = Mock(ComputeServer)
        MorpheusContext mockContext = Mock(MorpheusContext)
        MorpheusServices mockServices = Mock(MorpheusServices)
        MorpheusSynchronousComputeServerService mockComputeServerService = Mock(MorpheusSynchronousComputeServerService)

        mockServer.id >> 789L
        mockServer.resourcePool >> null
        mockContext.services >> mockServices
        mockServices.computeServer >> mockComputeServerService
        mockComputeServerService.get(789L) >> fetchedServer
        fetchedServer.resourcePool >> [serverGroup: [id: 999L]]

        when:
        Long result = CommonUtils.getServerGroupId(mockServer, mockContext)

        then:
        result == 999L
    }

    def "getServerGroupId returns null when resourcePool is completely unavailable"() {
        given:
        ComputeServer mockServer = Mock(ComputeServer)
        ComputeServer fetchedServer = Mock(ComputeServer)
        MorpheusContext mockContext = Mock(MorpheusContext)
        MorpheusServices mockServices = Mock(MorpheusServices)
        MorpheusSynchronousComputeServerService mockComputeServerService = Mock(MorpheusSynchronousComputeServerService)

        mockServer.id >> 789L
        mockServer.resourcePool >> null

        mockContext.services >> mockServices
        mockServices.computeServer >> mockComputeServerService
        mockComputeServerService.get(789L) >> fetchedServer
        fetchedServer.resourcePool >> null

        when:
        Long result = CommonUtils.getServerGroupId(mockServer, mockContext)

        then:
        result == null
    }

    def "findReachableIp returns null for empty or null IP list"() {
        expect:
        CommonUtils.findReachableIp(null) == null
        CommonUtils.findReachableIp([]) == null
    }



    def "isPortReachable returns false for null or empty IP"() {
        expect:
        !CommonUtils.isPortReachable(null, 22, 1000)
        !CommonUtils.isPortReachable('', 22, 1000)
        !CommonUtils.isPortReachable('  ', 22, 1000)
    }

    def "isPingReachable returns false for null or empty IP"() {
        expect:
        !CommonUtils.isPingReachable(null, 1000)
        !CommonUtils.isPingReachable('', 1000)
        !CommonUtils.isPingReachable('  ', 1000)
    }

    def "isPortReachable handles unreachable ports"() {
        expect:
        // Using a definitely unreachable port
        !CommonUtils.isPortReachable('192.0.2.1', 9999, 100)
    }

    def "isPingReachable handles unreachable IPs"() {
        expect:
        // Using a definitely unreachable IP (RFC 5737 TEST-NET-1)
        !CommonUtils.isPingReachable('192.0.2.1', 100)
    }

    def "findReachableIp with list of only invalid IPs"() {
        given:
        List<String> ipAddresses = [null, null, '', '   ']

        when:
        String result = CommonUtils.findReachableIp(ipAddresses)

        then:
        result == null || result.trim().isEmpty()
    }


    def "getIpType handles null IP"() {
        expect:
        CommonUtils.getIpType(null) == NetAddress.AddressType.IPV4
    }

    def "getMorpheusOsTypeCode handles null input"() {
        when:
        CommonUtils.getMorpheusOsTypeCode(null)

        then:
        thrown(NullPointerException)
    }

    def "isPortReachable with custom timeout"() {
        expect:
        !CommonUtils.isPortReachable('192.0.2.1', 22, 50)
    }

    def "isPingReachable with custom timeout"() {
        expect:
        !CommonUtils.isPingReachable('192.0.2.1', 50)
    }

    def "getInterfaceTypeCode handles uppercase"() {
        expect:
        CommonUtils.getInterfaceTypeCode('E1000') == HarvesterConstants.E1000_INTERFACE_TYPE
        CommonUtils.getInterfaceTypeCode('E1000E') == HarvesterConstants.E1000_INTERFACE_TYPE
        CommonUtils.getInterfaceTypeCode('VIRTIO') == HarvesterConstants.VIRTIO_INTERFACE_TYPE
    }

    def "param with various operators"() {
        expect:
        CommonUtils.param('key', 'value', op).operator == op

        where:
        op << ['=', '!=', '>', '<', '>=', '<=', 'in', 'like']
    }

    def "getServerGroupId handles server with partial resource pool"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockComputeServerService = Mock(MorpheusSynchronousComputeServerService)
        ComputeServer mockServer = Mock(ComputeServer)
        ComputeServer refreshedServer = Mock(ComputeServer)
        CloudPool mockPool = Mock(CloudPool)
        ComputeServerGroup mockServerGroup = Mock(ComputeServerGroup)
        
        mockServer.id >> 100L
        mockServer.resourcePool >> [:]  // Empty map without serverGroup
        mockContext.services >> mockServices
        mockServices.computeServer >> mockComputeServerService
        mockComputeServerService.get(100L) >> refreshedServer
        refreshedServer.resourcePool >> mockPool
        mockPool.serverGroup >> mockServerGroup
        mockServerGroup.id >> 999L

        when:
        Long result = CommonUtils.getServerGroupId(mockServer, mockContext)

        then:
        result == 999L
    }


    def "getMorpheusOsTypeCode case insensitivity"() {
        expect:
        CommonUtils.getMorpheusOsTypeCode('RHEL9') == 'redhat'
        CommonUtils.getMorpheusOsTypeCode('Rhel9') == 'redhat'
        CommonUtils.getMorpheusOsTypeCode('WINDOWS2K22') == 'windows'  // Falls through to contains check
        CommonUtils.getMorpheusOsTypeCode('Windows2k22') == 'windows'  // Falls through to contains check
    }

    def "isPortReachable handles whitespace in IP"() {
        expect:
        !CommonUtils.isPortReachable('  192.0.2.1  ', 22, 100)
    }

    def "isPingReachable handles whitespace in IP"() {
        expect:
        !CommonUtils.isPingReachable('  192.0.2.1  ', 100)
    }

    def "isPortReachable returns true for listening local socket"() {
        given:
        ServerSocket serverSocket = new ServerSocket(0)
        int port = serverSocket.localPort

        when:
        boolean result = CommonUtils.isPortReachable('127.0.0.1', port, 500)

        then:
        result

        cleanup:
        serverSocket?.close()
    }

    def "isPingReachable returns true for localhost"() {
        expect:
        CommonUtils.isPingReachable('127.0.0.1', 1000)
    }

    def "findReachableIp prioritizes SSH reachable over others"() {
        given:
        ServerSocket serverSocket = new ServerSocket(0)
        int port = serverSocket.localPort
        List<String> ipAddresses = ['192.0.2.1', '127.0.0.1']

        when:
        String result = CommonUtils.findReachableIp(ipAddresses, port, 500)

        then:
        result == '127.0.0.1'

        cleanup:
        serverSocket?.close()
    }

    def "findReachableIp falls back to ping reachable when SSH port is invalid"() {
        given:
        List<String> ipAddresses = ['127.0.0.1']

        when:
        String result = CommonUtils.findReachableIp(ipAddresses, -1, 1000)

        then:
        result == '127.0.0.1'
    }

    def "findReachableIp returns first valid IP when neither SSH nor ping works"() {
        given:
        List<String> ipAddresses = ['198.51.100.10', '203.0.113.20']

        when:
        String result = CommonUtils.findReachableIp(ipAddresses, 65000, 50)

        then:
        result == '198.51.100.10'
    }

    def "getServerGroupId does not call context when server already has serverGroup id"() {
        given:
        ComputeServer mockServer = Mock(ComputeServer)
        def mockContext = Mock(MorpheusContext)
        mockServer.resourcePool >> [serverGroup: [id: 1234L]]

        when:
        Long result = CommonUtils.getServerGroupId(mockServer, mockContext)

        then:
        result == 1234L
        0 * mockContext._
    }

    def "getServerGroupId returns null when refreshed server has resourcePool without serverGroup"() {
        given:
        ComputeServer mockServer = Mock(ComputeServer)
        ComputeServer refreshedServer = Mock(ComputeServer)
        MorpheusContext mockContext = Mock(MorpheusContext)
        MorpheusServices mockServices = Mock(MorpheusServices)
        MorpheusSynchronousComputeServerService mockComputeServerService = Mock(MorpheusSynchronousComputeServerService)

        mockServer.id >> 123L
        mockServer.resourcePool >> null
        mockContext.services >> mockServices
        mockServices.computeServer >> mockComputeServerService
        mockComputeServerService.get(123L) >> refreshedServer
        refreshedServer.resourcePool >> [:]

        when:
        Long result = CommonUtils.getServerGroupId(mockServer, mockContext)

        then:
        result == null
    }

    // ── Additional coverage for edge cases ──────────────────────────────

    def "getNetworkTypeCode handles empty string"() {
        expect:
        CommonUtils.getNetworkTypeCode('') == HarvesterConstants.HARVESTER_NETWORK_TYPE
    }

    def "getInterfaceTypeCode returns VIRTIO for unknown models"() {
        expect:
        CommonUtils.getInterfaceTypeCode('unknown-model') == HarvesterConstants.VIRTIO_INTERFACE_TYPE
    }

    def "findReachableIp handles exception during reachability check"() {
        given:
        def ipAddresses = ['invalid---ip']

        when:
        def result = CommonUtils.findReachableIp(ipAddresses, 22, 1000)

        then:
        result == 'invalid---ip' // Returns first IP when none are reachable
    }

    def "getMorpheusOsTypeCode correctly handles different Windows versions"() {
        expect:
        CommonUtils.getMorpheusOsTypeCode('windows10') == 'windows.10'
        CommonUtils.getMorpheusOsTypeCode('windows11') == 'windows.11'
        CommonUtils.getMorpheusOsTypeCode('windows2019') == 'windows.server.2019'
    }

    def "harvesterGuestMemoryMi subtracts Harvester's reserved memory for normal sized VMs"() {
        expect:
        CommonUtils.harvesterGuestMemoryMi(4096L) == 3996L
        CommonUtils.harvesterGuestMemoryMi(512L) == 412L
        CommonUtils.harvesterGuestMemoryMi(256L) == 256L
        CommonUtils.harvesterGuestMemoryMi(128L) == 128L
    }
}
