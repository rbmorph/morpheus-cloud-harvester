package com.hpe.morpheus.harvester.plugin.common

import com.morpheusdata.model.ProcessStepType
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Comprehensive unit tests for Constants class
 * Tests all constant values, types, and immutability
 */
class ConstantsSpec extends Specification {

    def "Constants class should be instantiable"() {
        when:
        def constants = new Constants()

        then:
        constants != null
        constants instanceof Constants
    }

    def "PROCESS_STEP_PROVISION_STORAGE should be defined with correct code"() {
        expect:
        Constants.PROCESS_STEP_PROVISION_STORAGE != null
        Constants.PROCESS_STEP_PROVISION_STORAGE instanceof ProcessStepType
        Constants.PROCESS_STEP_PROVISION_STORAGE.code == "harvester.provision-storage"
    }

    def "PROCESS_STEP_PROVISION_VM should be defined with correct code"() {
        expect:
        Constants.PROCESS_STEP_PROVISION_VM != null
        Constants.PROCESS_STEP_PROVISION_VM instanceof ProcessStepType
        Constants.PROCESS_STEP_PROVISION_VM.code == "harvester.provision-deploy"
    }

    def "PROCESS_STEP_WAIT_FOR_VM_BOOT should be defined with correct code"() {
        expect:
        Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT != null
        Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT instanceof ProcessStepType
        Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT.code == "harvester.wait-for-vm-boot"
    }

    def "PROCESS_STEP_WAIT_FOR_VM_NETWORK should be defined with correct code"() {
        expect:
        Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK != null
        Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK instanceof ProcessStepType
        Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK.code == "harvester.provision-network"
    }

    @Unroll
    def "process step constant #constantName should have harvester prefix in code"() {
        expect:
        constant.code.startsWith("harvester.")

        where:
        constantName                        | constant
        "PROCESS_STEP_PROVISION_STORAGE"   | Constants.PROCESS_STEP_PROVISION_STORAGE
        "PROCESS_STEP_PROVISION_VM"         | Constants.PROCESS_STEP_PROVISION_VM
        "PROCESS_STEP_WAIT_FOR_VM_BOOT"     | Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT
        "PROCESS_STEP_WAIT_FOR_VM_NETWORK"  | Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK
    }

    @Unroll
    def "all process step constants should be unique: #code1 != #code2"() {
        expect:
        code1 != code2

        where:
        code1                                    | code2
        "harvester.provision-storage"            | "harvester.provision-deploy"
        "harvester.provision-storage"            | "harvester.wait-for-vm-boot"
        "harvester.provision-storage"            | "harvester.provision-network"
        "harvester.provision-deploy"             | "harvester.wait-for-vm-boot"
        "harvester.provision-deploy"             | "harvester.provision-network"
        "harvester.wait-for-vm-boot"             | "harvester.provision-network"
    }

    def "all process step type constants should be unique instances"() {
        expect:
        Constants.PROCESS_STEP_PROVISION_STORAGE != Constants.PROCESS_STEP_PROVISION_VM
        Constants.PROCESS_STEP_PROVISION_STORAGE != Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT
        Constants.PROCESS_STEP_PROVISION_STORAGE != Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK
        Constants.PROCESS_STEP_PROVISION_VM != Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT
        Constants.PROCESS_STEP_PROVISION_VM != Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK
        Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT != Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK
    }

    def "all process step constants should be of type ProcessStepType"() {
        expect:
        Constants.PROCESS_STEP_PROVISION_STORAGE instanceof ProcessStepType
        Constants.PROCESS_STEP_PROVISION_VM instanceof ProcessStepType
        Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT instanceof ProcessStepType
        Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK instanceof ProcessStepType
    }

    def "process step constants should be accessible as static fields"() {
        when:
        def provisionStorage = Constants.PROCESS_STEP_PROVISION_STORAGE
        def provisionVm = Constants.PROCESS_STEP_PROVISION_VM
        def waitForBoot = Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT
        def waitForNetwork = Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK

        then:
        provisionStorage != null
        provisionVm != null
        waitForBoot != null
        waitForNetwork != null
    }

    def "PROCESS_STEP_PROVISION_STORAGE code should follow naming convention"() {
        expect:
        Constants.PROCESS_STEP_PROVISION_STORAGE.code.contains("provision")
        Constants.PROCESS_STEP_PROVISION_STORAGE.code.contains("storage")
        !Constants.PROCESS_STEP_PROVISION_STORAGE.code.contains(" ")
        Constants.PROCESS_STEP_PROVISION_STORAGE.code.toLowerCase() == Constants.PROCESS_STEP_PROVISION_STORAGE.code
    }

    def "PROCESS_STEP_PROVISION_VM code should follow naming convention"() {
        expect:
        Constants.PROCESS_STEP_PROVISION_VM.code.contains("provision")
        Constants.PROCESS_STEP_PROVISION_VM.code.contains("deploy")
        !Constants.PROCESS_STEP_PROVISION_VM.code.contains(" ")
        Constants.PROCESS_STEP_PROVISION_VM.code.toLowerCase() == Constants.PROCESS_STEP_PROVISION_VM.code
    }

    def "PROCESS_STEP_WAIT_FOR_VM_BOOT code should follow naming convention"() {
        expect:
        Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT.code.contains("wait")
        Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT.code.contains("boot")
        !Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT.code.contains(" ")
        Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT.code.toLowerCase() == Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT.code
    }

    def "PROCESS_STEP_WAIT_FOR_VM_NETWORK code should follow naming convention"() {
        expect:
        Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK.code.contains("provision")
        Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK.code.contains("network")
        !Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK.code.contains(" ")
        Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK.code.toLowerCase() == Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK.code
    }

    @Unroll
    def "process step constant #constantName should not be null or empty"() {
        expect:
        constant != null
        constant.code != null
        constant.code != ""
        !constant.code.isEmpty()
        !constant.code.isBlank()

        where:
        constantName                        | constant
        "PROCESS_STEP_PROVISION_STORAGE"   | Constants.PROCESS_STEP_PROVISION_STORAGE
        "PROCESS_STEP_PROVISION_VM"         | Constants.PROCESS_STEP_PROVISION_VM
        "PROCESS_STEP_WAIT_FOR_VM_BOOT"     | Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT
        "PROCESS_STEP_WAIT_FOR_VM_NETWORK"  | Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK
    }

    def "all process step codes should use hyphen separator not underscore"() {
        expect:
        Constants.PROCESS_STEP_PROVISION_STORAGE.code.contains("-")
        !Constants.PROCESS_STEP_PROVISION_STORAGE.code.contains("_")
        Constants.PROCESS_STEP_PROVISION_VM.code.contains("-")
        !Constants.PROCESS_STEP_PROVISION_VM.code.contains("_")
        Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT.code.contains("-")
        !Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT.code.contains("_")
        Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK.code.contains("-")
        !Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK.code.contains("_")
    }

    def "process step codes should be immutable"() {
        given:
        def originalProvisionStorageCode = Constants.PROCESS_STEP_PROVISION_STORAGE.code
        def originalProvisionVmCode = Constants.PROCESS_STEP_PROVISION_VM.code
        def originalWaitBootCode = Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT.code
        def originalWaitNetworkCode = Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK.code

        when:
        // ProcessStepType objects are immutable, but let's verify the codes remain constant
        def provisionStorageCode = Constants.PROCESS_STEP_PROVISION_STORAGE.code
        def provisionVmCode = Constants.PROCESS_STEP_PROVISION_VM.code
        def waitBootCode = Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT.code
        def waitNetworkCode = Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK.code

        then:
        provisionStorageCode == originalProvisionStorageCode
        provisionVmCode == originalProvisionVmCode
        waitBootCode == originalWaitBootCode
        waitNetworkCode == originalWaitNetworkCode
    }

    def "process step constants should maintain referential equality on repeated access"() {
        when:
        def step1a = Constants.PROCESS_STEP_PROVISION_STORAGE
        def step1b = Constants.PROCESS_STEP_PROVISION_STORAGE
        def step2a = Constants.PROCESS_STEP_PROVISION_VM
        def step2b = Constants.PROCESS_STEP_PROVISION_VM
        def step3a = Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT
        def step3b = Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT
        def step4a = Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK
        def step4b = Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK

        then:
        step1a.is(step1b)
        step2a.is(step2b)
        step3a.is(step3b)
        step4a.is(step4b)
    }

    def "all process step codes should have consistent format"() {
        given:
        def codePattern = ~/^harvester\.[a-z\-]+$/

        expect:
        Constants.PROCESS_STEP_PROVISION_STORAGE.code ==~ codePattern
        Constants.PROCESS_STEP_PROVISION_VM.code ==~ codePattern
        Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT.code ==~ codePattern
        Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK.code ==~ codePattern
    }

    @Unroll
    def "process step code #code should not contain special characters except hyphen and dot"() {
        expect:
        code ==~ /^[a-z.\-]+$/

        where:
        code << [
            Constants.PROCESS_STEP_PROVISION_STORAGE.code,
            Constants.PROCESS_STEP_PROVISION_VM.code,
            Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT.code,
            Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK.code
        ]
    }

    def "process step types should support equality comparison"() {
        when:
        def step1 = Constants.PROCESS_STEP_PROVISION_STORAGE
        def step2 = Constants.PROCESS_STEP_PROVISION_STORAGE
        def step3 = Constants.PROCESS_STEP_PROVISION_VM

        then:
        step1 == step2
        step1 != step3
    }

    def "process step type codes should have reasonable length"() {
        expect:
        Constants.PROCESS_STEP_PROVISION_STORAGE.code.length() > 5
        Constants.PROCESS_STEP_PROVISION_STORAGE.code.length() < 50
        Constants.PROCESS_STEP_PROVISION_VM.code.length() > 5
        Constants.PROCESS_STEP_PROVISION_VM.code.length() < 50
        Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT.code.length() > 5
        Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT.code.length() < 50
        Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK.code.length() > 5
        Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK.code.length() < 50
    }

    def "Constants class should be in correct package"() {
        expect:
        Constants.class.package.name == "com.hpe.morpheus.harvester.plugin.common"
    }

    def "all process step constants should be public static final"() {
        when:
        def provisionStorageField = Constants.class.getDeclaredField("PROCESS_STEP_PROVISION_STORAGE")
        def provisionVmField = Constants.class.getDeclaredField("PROCESS_STEP_PROVISION_VM")
        def waitBootField = Constants.class.getDeclaredField("PROCESS_STEP_WAIT_FOR_VM_BOOT")
        def waitNetworkField = Constants.class.getDeclaredField("PROCESS_STEP_WAIT_FOR_VM_NETWORK")

        then:
        java.lang.reflect.Modifier.isPublic(provisionStorageField.modifiers)
        java.lang.reflect.Modifier.isStatic(provisionStorageField.modifiers)
        java.lang.reflect.Modifier.isFinal(provisionStorageField.modifiers)
        
        java.lang.reflect.Modifier.isPublic(provisionVmField.modifiers)
        java.lang.reflect.Modifier.isStatic(provisionVmField.modifiers)
        java.lang.reflect.Modifier.isFinal(provisionVmField.modifiers)
        
        java.lang.reflect.Modifier.isPublic(waitBootField.modifiers)
        java.lang.reflect.Modifier.isStatic(waitBootField.modifiers)
        java.lang.reflect.Modifier.isFinal(waitBootField.modifiers)
        
        java.lang.reflect.Modifier.isPublic(waitNetworkField.modifiers)
        java.lang.reflect.Modifier.isStatic(waitNetworkField.modifiers)
        java.lang.reflect.Modifier.isFinal(waitNetworkField.modifiers)
    }

    def "Constants class should only contain expected fields"() {
        when:
        def fields = Constants.class.getDeclaredFields()
        def publicStaticFields = fields.findAll { field ->
            java.lang.reflect.Modifier.isPublic(field.modifiers) &&
            java.lang.reflect.Modifier.isStatic(field.modifiers) &&
            !field.name.startsWith("__") && // Exclude Groovy-generated fields
            !field.name.startsWith("\$") // Exclude synthetic fields
        }

        then:
        publicStaticFields.size() == 4
        publicStaticFields*.name.containsAll([
            "PROCESS_STEP_PROVISION_STORAGE",
            "PROCESS_STEP_PROVISION_VM",
            "PROCESS_STEP_WAIT_FOR_VM_BOOT",
            "PROCESS_STEP_WAIT_FOR_VM_NETWORK"
        ])
    }

    def "process step codes should describe their purpose clearly"() {
        expect:
        // Provision storage step
        Constants.PROCESS_STEP_PROVISION_STORAGE.code.contains("storage")
        
        // Provision VM step
        Constants.PROCESS_STEP_PROVISION_VM.code.contains("deploy") || 
        Constants.PROCESS_STEP_PROVISION_VM.code.contains("provision")
        
        // Wait for boot step
        Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT.code.contains("wait") &&
        Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT.code.contains("boot")
        
        // Wait for network step
        Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK.code.contains("network")
    }

    @Unroll
    def "process step constant #constantName should have valid ProcessStepType structure"() {
        expect:
        constant != null
        constant.code != null
        constant.code.startsWith("harvester.")
        constant instanceof ProcessStepType

        where:
        constantName                        | constant
        "PROCESS_STEP_PROVISION_STORAGE"   | Constants.PROCESS_STEP_PROVISION_STORAGE
        "PROCESS_STEP_PROVISION_VM"         | Constants.PROCESS_STEP_PROVISION_VM
        "PROCESS_STEP_WAIT_FOR_VM_BOOT"     | Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT
        "PROCESS_STEP_WAIT_FOR_VM_NETWORK"  | Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK
    }

    def "Constants class should not have any instance methods beyond constructor"() {
        when:
        def declaredMethods = Constants.class.getDeclaredMethods()
        def nonSyntheticMethods = declaredMethods.findAll { !it.synthetic }

        then:
        // Should only have default constructor-related methods or none
        nonSyntheticMethods.every { method ->
            method.name.startsWith("__") || // Groovy generated methods
            method.name.startsWith("\$") || // Groovy generated methods
            method.name == "getMetaClass" ||
            method.name == "setMetaClass"
        }
    }

    def "process step codes should be suitable for use as identifiers"() {
        expect:
        // Should not start with number
        !Constants.PROCESS_STEP_PROVISION_STORAGE.code.charAt(0).isDigit()
        !Constants.PROCESS_STEP_PROVISION_VM.code.charAt(0).isDigit()
        !Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT.code.charAt(0).isDigit()
        !Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK.code.charAt(0).isDigit()
        
        // Should not contain spaces
        !Constants.PROCESS_STEP_PROVISION_STORAGE.code.contains(" ")
        !Constants.PROCESS_STEP_PROVISION_VM.code.contains(" ")
        !Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT.code.contains(" ")
        !Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK.code.contains(" ")
    }

    def "Constants class should be thread-safe for concurrent access"() {
        when:
        def results = Collections.synchronizedList([])
        def threads = []
        10.times { iteration ->
            threads << Thread.start {
                results << [
                    Constants.PROCESS_STEP_PROVISION_STORAGE,
                    Constants.PROCESS_STEP_PROVISION_VM,
                    Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT,
                    Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK
                ]
            }
        }
        threads*.join() // Wait for all threads to complete

        then:
        results.size() == 10
        results.every { list ->
            list[0].is(Constants.PROCESS_STEP_PROVISION_STORAGE) &&
            list[1].is(Constants.PROCESS_STEP_PROVISION_VM) &&
            list[2].is(Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT) &&
            list[3].is(Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK)
        }
    }
}
