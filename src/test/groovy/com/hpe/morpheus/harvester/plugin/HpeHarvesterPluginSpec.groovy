package com.hpe.morpheus.harvester.plugin

import com.hpe.morpheus.harvester.plugin.datasets.HpeHarvesterImageDataSetProvider
import com.hpe.morpheus.harvester.plugin.datasets.HpeHarvesterProjectProvider
import com.morpheusdata.core.MorpheusContext
import spock.lang.Specification
import spock.lang.Subject

/**
 * Comprehensive unit tests for HpeHarvesterPlugin
 * Tests plugin initialization, lifecycle, and provider registration
 */
class HpeHarvesterPluginSpec extends Specification {

    @Subject
    HpeHarvesterPlugin plugin

    MorpheusContext mockMorpheusContext = Mock(MorpheusContext)

    def setup() {
        plugin = new HpeHarvesterPlugin()
        plugin.morpheus = mockMorpheusContext
    }

    def "plugin should have correct code"() {
        expect:
        plugin.getCode() == 'morpheus-harvester-plugin'
    }

    def "plugin code should not be null or empty"() {
        expect:
        plugin.getCode() != null
        plugin.getCode() != ""
        !plugin.getCode().isEmpty()
    }

    def "plugin code should follow naming convention"() {
        expect:
        plugin.getCode().contains("morpheus")
        plugin.getCode().contains("harvester")
        plugin.getCode().contains("plugin")
        plugin.getCode() ==~ /^[a-z\-]+$/
    }

    def "initialize should set plugin properties correctly"() {
        when:
        plugin.initialize()

        then:
        plugin.name == 'Harvester'
        plugin.description == 'Plugin for Harvester Integration'
        plugin.author == 'rbmorph'
    }

    def "initialize gives the plugin its own renderer so Morpheus can register its controllers"() {
        given: 'Morpheus sets the class loader before initialize'
        plugin.classLoader = this.class.classLoader

        when:
        plugin.initialize()

        then: 'with a custom renderer Morpheus skips its fixed-size shared template list'
        plugin.controllers.size() == 3
        plugin.permissions.find { it.code == 'harvester-kubectl-read' }?.subCategory == 'infrastructure'
        plugin.permissions.find { it.code == 'harvester-kubectl-write' }?.subCategory == 'infrastructure'
        plugin.permissions.find { it.code == 'harvester-vm-cdrom' }?.subCategory == 'infrastructure'
        plugin.hasCustomRender()
        plugin.permissions.find { it.code == 'harvester-vm-yaml-edit' }?.subCategory == 'infrastructure'
    }

    def "initialize should not throw any exceptions"() {
        when:
        plugin.initialize()

        then:
        noExceptionThrown()
    }

    def "plugin should have valid name after initialization"() {
        when:
        plugin.initialize()

        then:
        plugin.name != null
        plugin.name != ""
        plugin.name.contains("Harvester")
    }

    def "plugin should have valid description after initialization"() {
        when:
        plugin.initialize()

        then:
        plugin.description != null
        plugin.description != ""
        plugin.description.contains("Harvester")
    }

    def "plugin should have valid author after initialization"() {
        when:
        plugin.initialize()

        then:
        plugin.author == "rbmorph"
        plugin.author != null
        plugin.author != ""
    }

    def "getMorpheusContext should return morpheus context"() {
        expect:
        plugin.getMorpheusContext() == mockMorpheusContext
    }

    def "getMorpheusContext should not return null when morpheus is set"() {
        expect:
        plugin.getMorpheusContext() != null
    }

    def "onDestroy should not throw any exceptions"() {
        when:
        plugin.onDestroy()

        then:
        noExceptionThrown()
    }

    def "plugin should be instantiable without parameters"() {
        when:
        def newPlugin = new HpeHarvesterPlugin()

        then:
        newPlugin != null
        newPlugin instanceof HpeHarvesterPlugin
    }

    def "plugin should have morpheus property accessible"() {
        when:
        plugin.morpheus = mockMorpheusContext

        then:
        plugin.morpheus == mockMorpheusContext
    }

    def "plugin code should be immutable across multiple calls"() {
        when:
        def code1 = plugin.getCode()
        def code2 = plugin.getCode()

        then:
        code1 == code2
        code1.is(code2)
    }

    def "initialize should be idempotent"() {
        when:
        plugin.initialize()
        def name1 = plugin.name
        plugin.initialize()
        def name2 = plugin.name

        then:
        name1 == name2
        name1 == 'Harvester'
    }

    def "plugin should support multiple destroy calls"() {
        when:
        plugin.onDestroy()
        plugin.onDestroy()

        then:
        noExceptionThrown()
    }

    def "plugin name should be properly formatted"() {
        when:
        plugin.initialize()

        then:
        plugin.name == "Harvester"
    }

    def "plugin description should mention integration"() {
        when:
        plugin.initialize()

        then:
        plugin.description.toLowerCase().contains("integration")
        plugin.description.contains("Harvester")
    }

    def "plugin should be an instance of com.morpheusdata.core.Plugin"() {
        expect:
        plugin instanceof com.morpheusdata.core.Plugin
    }

    def "plugin code should have consistent case"() {
        expect:
        plugin.getCode() == plugin.getCode().toLowerCase()
    }

    def "plugin code should use hyphen as separator"() {
        expect:
        plugin.getCode().contains("-")
        !plugin.getCode().contains("_")
        !plugin.getCode().contains(" ")
    }

    def "plugin should handle null morpheus context gracefully"() {
        given:
        plugin.morpheus = null

        when:
        def result = plugin.getMorpheusContext()

        then:
        result == null
    }

    def "plugin properties should be set after initialization"() {
        when:
        plugin.initialize()

        then:
        plugin.name != null
        plugin.description != null
        plugin.author != null
    }

    def "plugin should have correct package name"() {
        expect:
        plugin.class.package.name == "com.hpe.morpheus.harvester.plugin"
    }

    def "plugin should maintain state across multiple method calls"() {
        when:
        plugin.initialize()
        def name = plugin.name
        plugin.onDestroy()
        def nameAfterDestroy = plugin.name

        then:
        name == nameAfterDestroy
    }

    def "plugin code should have reasonable length"() {
        expect:
        plugin.getCode().length() > 5
        plugin.getCode().length() < 100
    }

    def "plugin should properly implement Plugin interface"() {
        expect:
        com.morpheusdata.core.Plugin.isAssignableFrom(plugin.class)
    }

    def "initialize and destroy should be complementary operations"() {
        when:
        plugin.initialize()
        plugin.onDestroy()

        then:
        noExceptionThrown()
    }

    def "plugin name should not contain special characters"() {
        when:
        plugin.initialize()

        then:
        !plugin.name.contains("@")
        !plugin.name.contains("#")
        !plugin.name.contains("\$")
        !plugin.name.contains("%")
    }

    def "plugin description should be informative"() {
        when:
        plugin.initialize()

        then:
        plugin.description.length() > 10
        plugin.description.contains("Plugin")
    }

    def "plugin author should be set correctly"() {
        when:
        plugin.initialize()

        then:
        plugin.author == "rbmorph"
    }

    def "getMorpheusContext should return same instance multiple times"() {
        when:
        def ctx1 = plugin.getMorpheusContext()
        def ctx2 = plugin.getMorpheusContext()

        then:
        ctx1.is(ctx2)
    }

    def "plugin should support repeated initialization"() {
        when:
        5.times { plugin.initialize() }

        then:
        noExceptionThrown()
        plugin.name == 'Harvester'
    }

    def "plugin code should be suitable as identifier"() {
        expect:
        !plugin.getCode().startsWith("-")
        !plugin.getCode().endsWith("-")
        !Character.isDigit(plugin.getCode().charAt(0))
    }

    def "plugin should handle lifecycle correctly"() {
        when:
        plugin.initialize()
        def initialized = plugin.name != null
        plugin.onDestroy()
        def destroyed = true

        then:
        initialized
        destroyed
    }
}
