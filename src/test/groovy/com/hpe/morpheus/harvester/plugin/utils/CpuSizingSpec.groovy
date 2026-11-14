// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.utils

import com.hpe.morpheus.harvester.plugin.HpeHarvesterProvisionProvider
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.request.ResizeV2Request
import spock.lang.Specification
import spock.lang.Unroll

class CpuSizingSpec extends Specification {

    static ServicePlan customCpuPlan() {
        def p = new ServicePlan()
        p.customCpu = true
        p.customCores = false
        p.maxCpu = 2L
        p.maxCores = 2L
        return p
    }

    static ServicePlan fixedPlan(Long cores) {
        def p = new ServicePlan()
        p.customCpu = false
        p.maxCores = cores
        p.maxCpu = 1L
        return p
    }

    @Unroll
    def "vcpusFor(#desc) = #expected"() {
        expect:
        CommonUtils.vcpusFor(plan, maxCpu, maxCores) == expected

        where:
        desc                                   | plan              | maxCpu | maxCores || expected
        'custom plan uses the CPU value'       | customCpuPlan()   | 6      | 2        || 6L
        'custom plan without CPU uses cores'   | customCpuPlan()   | null   | 4        || 4L
        'fixed plan uses its cores'            | fixedPlan(8)      | 1      | 8        || 8L
        'fixed plan, cores only on the plan'   | fixedPlan(4)      | null   | null     || 4L
        'nothing set'                          | null              | null   | null     || 1L
        'CPU as text'                          | customCpuPlan()   | '3'    | 2        || 3L
    }

    def "requestedCpu reads the CPU value from the reconfigure options"() {
        expect:
        CommonUtils.requestedCpu([servicePlanOptions: [maxCpu: '4']]) == 4L
        CommonUtils.requestedCpu([maxCpu: 3]) == 3L
        CommonUtils.requestedCpu([config: [maxCpu: 5]]) == 5L
        CommonUtils.requestedCpu([:]) == null
        CommonUtils.requestedCpu([maxCpu: 0]) == null
    }

    def "applyPlanCpu turns the CPU value into the request's cores for the CPU-sized plan"() {
        given:
        def request = new ResizeV2Request(plan: customCpuPlan(), maxCores: 2L, coresPerSocket: 1L)

        when:
        HpeHarvesterProvisionProvider.applyPlanCpu(request, [servicePlanOptions: [maxCpu: 6]])

        then:
        request.maxCores == 6L
    }

    def "applyPlanCpu leaves a fixed plan's cores alone"() {
        given:
        def request = new ResizeV2Request(plan: fixedPlan(8), maxCores: 8L)

        when:
        HpeHarvesterProvisionProvider.applyPlanCpu(request, [servicePlanOptions: [maxCpu: 1]])

        then:
        request.maxCores == 8L
    }

    def "applyPlanCpu takes cores per socket from the form, else from a fixed plan"() {
        given:
        def fixed = fixedPlan(8)
        fixed.coresPerSocket = 4L
        def custom = new ServicePlan(customCores: true, coresPerSocket: 1L)
        def fromForm = new ResizeV2Request(plan: custom, maxCores: 8L)
        def fromPlan = new ResizeV2Request(plan: fixed)
        def customNoChoice = new ResizeV2Request(plan: custom, maxCores: 4L)

        when:
        HpeHarvesterProvisionProvider.applyPlanCpu(fromForm, [servicePlanOptions: [coresPerSocket: '2']])
        HpeHarvesterProvisionProvider.applyPlanCpu(fromPlan, [:])
        HpeHarvesterProvisionProvider.applyPlanCpu(customNoChoice, [:])

        then:
        fromForm.coresPerSocket == 2L
        fromPlan.maxCores == 8L
        fromPlan.coresPerSocket == 4L
        customNoChoice.coresPerSocket == null
    }

    def "with custom CPU and custom cores, cores are the vCPUs and CPU is the socket count"() {
        given:
        def plan = new ServicePlan(customCpu: true, customCores: true, coresPerSocket: 1L)
        def request = new ResizeV2Request(plan: plan, maxCores: 8L)

        when:
        HpeHarvesterProvisionProvider.applyPlanCpu(request, [servicePlanOptions: [maxCpu: 2]])

        then:
        CommonUtils.vcpusFor(plan, 2, 8) == 8L
        request.maxCores == 8L
        request.coresPerSocket == 4L
        com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning.coresPerSocketFromSockets(plan, 6L, 4) == null
    }
}
