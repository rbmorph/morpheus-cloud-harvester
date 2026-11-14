// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.utils

import spock.lang.Specification
import spock.lang.Unroll

class CpuTopologySpec extends Specification {

    @Unroll
    def "split(#vcpus, #cps) = #expected"() {
        expect:
        CpuTopology.split(vcpus, cps) == expected

        where:
        vcpus | cps  || expected
        8L    | 4L   || [cores: 4L, sockets: 2L]
        8L    | 1L   || [cores: 1L, sockets: 8L]
        8L    | 8L   || [cores: 8L, sockets: 1L]
        4L    | null || [cores: 4L, sockets: 1L]
        null  | null || [cores: 1L, sockets: 1L]
        6L    | 4L   || null
        2L    | 4L   || null
    }

    def "firstPositive skips empty and zero values"() {
        expect:
        CpuTopology.firstPositive(null, 0, '', '3', 5L) == 3L
        CpuTopology.firstPositive(null, null) == null
    }

    def "parseRatio reads Harvester's setting and falls back to 4"() {
        expect:
        CpuTopology.parseRatio([value: '6']) == 6L
        CpuTopology.parseRatio([value: '', default: '4']) == 4L
        CpuTopology.parseRatio([value: '50']) == 4L
        CpuTopology.parseRatio([value: 'x']) == 4L
        CpuTopology.parseRatio(null) == 4L
    }

    def "isHotplugEnabled reads the Harvester annotation"() {
        expect:
        CpuTopology.isHotplugEnabled([metadata: [annotations: [(CpuTopology.ANNOTATION_HOTPLUG): 'true']]])
        !CpuTopology.isHotplugEnabled([metadata: [annotations: [(CpuTopology.ANNOTATION_HOTPLUG): 'false']]])
        !CpuTopology.isHotplugEnabled([metadata: [:]])
    }

    def "applyToDomain without hot add uses the Harvester UI shape"() {
        given:
        Map domain = [cpu: [cores: 9], resources: [requests: [cpu: '1']]]

        when:
        CpuTopology.applyToDomain(domain, [cores: 2L, sockets: 2L], 4096L, false, 4L)

        then:
        domain.cpu == [cores: 2L, sockets: 2L, threads: 1L]
        domain.memory == [guest: '3996Mi']
        domain.resources.limits == [cpu: '4', memory: '4096Mi']
        domain.resources.requests == [cpu: '1']
    }

    def "applyToDomain with hot add sets the maximums"() {
        given:
        Map domain = [:]

        when:
        CpuTopology.applyToDomain(domain, [cores: 2L, sockets: 1L], 2048L, true, 4L)

        then:
        domain.cpu == [cores: 2L, sockets: 1L, threads: 1L, maxSockets: 4L]
        domain.memory == [guest: '2048Mi', maxGuest: '8192Mi']
        domain.resources.limits == [cpu: '8', memory: '8192Mi']
    }

    @Unroll
    def "toMi(#q) = #expected"() {
        expect:
        CpuTopology.toMi(q) == expected

        where:
        q          || expected
        '2048Mi'   || 2048L
        '2Gi'      || 2048L
        '1048576Ki'|| 1024L
        null       || null
        'abc'      || null
    }

    @Unroll
    def "hotAddBlocker: #desc"() {
        given:
        Map domain = [cpu: [cores: 2, sockets: 1, maxSockets: 4], memory: [guest: '2048Mi', maxGuest: '8192Mi']]

        expect:
        (CpuTopology.hotAddBlocker(domain, vcpus, cps, memMi) == null) == live

        where:
        desc                          | vcpus | cps  | memMi || live
        'add a socket and memory'     | 4L    | 2L   | 4096L || true
        'no change'                   | 2L    | 2L   | 2048L || true
        'up to the maximum'           | 8L    | null | 8192L || true
        'past the CPU maximum'        | 10L   | null | 2048L || false
        'past the memory maximum'     | 2L    | null | 9000L || false
        'remove memory'               | 2L    | 2L   | 1024L || false
        'cores per socket changes'    | 4L    | 4L   | 2048L || false
        'not a whole socket'          | 3L    | null | 2048L || false
    }
}
