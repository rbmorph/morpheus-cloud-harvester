// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.utils

import com.morpheusdata.model.ComputeServer
import spock.lang.Specification
import spock.lang.Unroll

class GuestConsoleTypeSpec extends Specification {

    @Unroll
    def "guest console for '#value' is #expected"() {
        expect:
        CommonUtils.guestConsoleTypeFor(value) == expected

        where:
        value       || expected
        'windows'   || ComputeServer.GuestConsoleType.rdp
        'Windows'   || ComputeServer.GuestConsoleType.rdp
        'linux'     || ComputeServer.GuestConsoleType.ssh
        'ubuntu'    || ComputeServer.GuestConsoleType.ssh
        null        || ComputeServer.GuestConsoleType.ssh
    }
}
