package com.hpe.morpheus.harvester.plugin.utils.parsers

import spock.lang.Specification

/**
 * Unit tests for CommonParser class
 */
class CommonParserSpec extends Specification {

    def "test parseCpuValue with milli-cores"() {
        expect:
        CommonParser.parseCpuValue("4000m") == 4L
    }

    def "test parseCpuValue with whole cores"() {
        expect:
        CommonParser.parseCpuValue("4") == 4L
    }

    def "test parseCpuValue with invalid input"() {
        expect:
        CommonParser.parseCpuValue("invalid") == 0L
    }

    def "test parseMemoryValue with Ki units"() {
        expect:
        CommonParser.parseMemoryValue("1024Ki") == 1024L * 1024L
    }

    def "test parseMemoryValue with Mi units"() {
        expect:
        CommonParser.parseMemoryValue("1Mi") == 1024L * 1024L
    }

    def "test parseMemoryValue with Gi units"() {
        expect:
        CommonParser.parseMemoryValue("1Gi") == 1024L * 1024L * 1024L
    }

    def "test parseMemoryValue with Ti units"() {
        expect:
        CommonParser.parseMemoryValue("1Ti") == 1024L * 1024L * 1024L * 1024L
    }

    def "test parseMemoryValue with lowercase m units"() {
        expect:
        CommonParser.parseMemoryValue("22763326668800m") == 22763326668800L * 1024L * 1024L
    }

    def "test parseMemoryValue with lowercase k units"() {
        expect:
        CommonParser.parseMemoryValue("117818k") == 117818L * 1024L
    }

    def "test parseMemoryValue with invalid input"() {
        expect:
        CommonParser.parseMemoryValue("invalid") == 0L
    }

    def "test parseCpuValue with null input"() {
        expect:
        CommonParser.parseCpuValue(null) == 0L
    }

    def "test parseCpuValue with empty string"() {
        expect:
        CommonParser.parseCpuValue("") == 0L
    }

    def "test parseCpuValue with nano-cores"() {
        expect:
        CommonParser.parseCpuValue("1000000000n") == 1L
        CommonParser.parseCpuValue("500000000n") == 0L
    }

    def "test parseCpuValue with zero cores"() {
        expect:
        CommonParser.parseCpuValue("0") == 0L
        CommonParser.parseCpuValue("0m") == 0L
    }

    def "test parseCpuValue with large values"() {
        expect:
        CommonParser.parseCpuValue("16") == 16L
        CommonParser.parseCpuValue("16000m") == 16L
    }

    def "test parseMemoryValue with null input"() {
        expect:
        CommonParser.parseMemoryValue(null) == 0L
    }

    def "test parseMemoryValue with empty string"() {
        expect:
        CommonParser.parseMemoryValue("") == 0L
    }

    def "test parseMemoryValue with plain bytes"() {
        expect:
        CommonParser.parseMemoryValue("1024") == 1024L
        CommonParser.parseMemoryValue("2048") == 2048L
    }

    def "test parseMemoryValue with zero values"() {
        expect:
        CommonParser.parseMemoryValue("0") == 0L
        CommonParser.parseMemoryValue("0Ki") == 0L
        CommonParser.parseMemoryValue("0Mi") == 0L
        CommonParser.parseMemoryValue("0Gi") == 0L
    }

    def "test parseMemoryValue with large values"() {
        expect:
        CommonParser.parseMemoryValue("512Gi") == 512L * 1024L * 1024L * 1024L
        CommonParser.parseMemoryValue("1024Mi") == 1024L * 1024L * 1024L
    }

    def "test parseMemoryValue with mixed case units"() {
        expect:
        CommonParser.parseMemoryValue("100K") == 100L * 1024L
        CommonParser.parseMemoryValue("100M") == 100L * 1024L * 1024L
    }

    def "test parseCpuValue handles object input"() {
        expect:
        CommonParser.parseCpuValue(Integer.valueOf(4)) == 4L
        CommonParser.parseCpuValue(Long.valueOf(8)) == 8L
    }

    def "test parseMemoryValue handles object input"() {
        expect:
        CommonParser.parseMemoryValue(Integer.valueOf(1024)) == 1024L
        CommonParser.parseMemoryValue(Long.valueOf(2048)) == 2048L
    }
}
