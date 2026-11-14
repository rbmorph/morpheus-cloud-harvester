package com.hpe.morpheus.harvester.plugin.utils

import spock.lang.Specification

class MorpheusExceptionSpec extends Specification {

    def "MorpheusException can be created with no arguments"() {
        when:
        MorpheusException exception = new MorpheusException()

        then:
        exception instanceof Exception
        exception.message == null
    }

    def "MorpheusException can be created with a message"() {
        given:
        String errorMessage = "Test error message"

        when:
        MorpheusException exception = new MorpheusException(errorMessage)

        then:
        exception instanceof Exception
        exception.message == errorMessage
    }

    def "MorpheusException with message can be thrown and caught"() {
        given:
        String errorMessage = "Critical failure in Harvester plugin"

        when:
        throw new MorpheusException(errorMessage)

        then:
        MorpheusException e = thrown()
        e.message == errorMessage
    }

    def "MorpheusException without message can be thrown and caught"() {
        when:
        throw new MorpheusException()

        then:
        MorpheusException e = thrown()
        e.message == null
    }

    def "MorpheusException is a subclass of Exception"() {
        expect:
        Exception.isAssignableFrom(MorpheusException)
    }

    def "MorpheusException preserves stack trace"() {
        when:
        throwNestedError()

        then:
        def e = thrown(MorpheusException)
        e.stackTrace.length > 0
    }

    def "MorpheusException can be caught as Exception"() {
        when:
        throw new MorpheusException("test")

        then:
        Exception e = thrown()
        e instanceof MorpheusException
        e.message == "test"
    }

    def "MorpheusException with null message"() {
        when:
        String nullMsg = null
        new MorpheusException(nullMsg)

        then:
        thrown(groovy.lang.GroovyRuntimeException)
    }

    def "MorpheusException with empty message"() {
        when:
        def exception = new MorpheusException("")

        then:
        exception.message == ""
    }

    def "MorpheusException with long message"() {
        given:
        String longMessage = "Error: " * 1000

        when:
        def exception = new MorpheusException(longMessage)

        then:
        exception.message == longMessage
        exception.message.length() > 1000
    }

    def "MorpheusException with special characters in message"() {
        given:
        String specialMessage = "Error: 特殊字符 \n\t\r Special!@#\$%^&*()"

        when:
        def exception = new MorpheusException(specialMessage)

        then:
        exception.message == specialMessage
    }

    private void throwNestedError() {
        throw new MorpheusException("Nested error")
    }
}
