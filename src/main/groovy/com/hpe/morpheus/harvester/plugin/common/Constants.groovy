package com.hpe.morpheus.harvester.plugin.common

import com.morpheusdata.model.ProcessStepType
import groovy.transform.CompileStatic

/**
 * Constants class for Harvester plugin operations.
 */
@CompileStatic
class Constants {

    // Process Step Type Constants
    public static final ProcessStepType PROCESS_STEP_PROVISION_STORAGE = ProcessStepType.forCode("harvester.provision-storage")
    public static final ProcessStepType PROCESS_STEP_PROVISION_VM = ProcessStepType.forCode("harvester.provision-deploy")
    public static final ProcessStepType PROCESS_STEP_WAIT_FOR_VM_BOOT = ProcessStepType.forCode("harvester.wait-for-vm-boot")
    public static final ProcessStepType PROCESS_STEP_WAIT_FOR_VM_NETWORK = ProcessStepType.forCode("harvester.provision-network")
}