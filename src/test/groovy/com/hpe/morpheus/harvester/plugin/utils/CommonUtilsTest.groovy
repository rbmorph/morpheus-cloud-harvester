package com.hpe.morpheus.harvester.plugin.utils

import spock.lang.Specification

class CommonUtilsTest extends Specification {

    def "should map known Harvester OS types to Morpheus OS type codes"() {
        expect:
        CommonUtils.getMorpheusOsTypeCode(harvesterOsType) == expectedCode

        where:
        harvesterOsType           | expectedCode
        'centos.stream10'         | 'cent'
        'centos-stream9'          | 'cent'
        'fedora'                  | 'fedora'
        'rhel7'                   | 'redhat.7'
        'rhel8'                   | 'redhat.8'
        'rhel9'                   | 'redhat'
        'windows10'               | 'windows.10'
        'windows11'               | 'windows.11'
        'windows2k16'             | 'windows.server.2016'
        'windows2019'             | 'windows.server.2019'
        'windows2022'             | 'windows.server.2022'
    }

    def "should map unknown but recognizable OS types to generic Morpheus codes"() {
        expect:
        CommonUtils.getMorpheusOsTypeCode(harvesterOsType) == expectedCode

        where:
        harvesterOsType           | expectedCode
        'windowsXP'               | 'windows'
        'rhel-server'             | 'redhat'
        'centos7'                 | 'cent'
        'ubuntu20.04'             | 'ubuntu'
        'debian10'                | 'debian'
        'opensuse-leap'           | 'suse'
        'fedora-coreos'           | 'fedora'
        'macos'                   | 'mac'
        'darwin'                  | 'mac'
    }

    def "should return 'other.64' for completely unknown OS types"() {
        expect:
        CommonUtils.getMorpheusOsTypeCode('unknownOS') == 'other.64'
    }
}
