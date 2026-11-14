package com.hpe.morpheus.harvester.plugin.utils

import spock.lang.Specification
import spock.lang.Unroll

class DataUnitConverterUtilSpec extends Specification {

    @Unroll
    def "dataUnitConverter converts #value #fromUnit to #toUnit correctly"() {
        expect:
        DataUnitConverterUtil.dataUnitConverter(value, fromUnit, toUnit) == expected

        where:
        value | fromUnit                            | toUnit                             | expected
        1024  | DataUnitConverterUtil.DataUnit.B    | DataUnitConverterUtil.DataUnit.KiB | 1
        1     | DataUnitConverterUtil.DataUnit.KiB  | DataUnitConverterUtil.DataUnit.B   | 1024
        1024  | DataUnitConverterUtil.DataUnit.KiB  | DataUnitConverterUtil.DataUnit.MiB | 1
        1     | DataUnitConverterUtil.DataUnit.MiB  | DataUnitConverterUtil.DataUnit.KiB | 1024
        1024  | DataUnitConverterUtil.DataUnit.MiB  | DataUnitConverterUtil.DataUnit.GiB | 1
        1     | DataUnitConverterUtil.DataUnit.GiB  | DataUnitConverterUtil.DataUnit.MiB | 1024
        1024  | DataUnitConverterUtil.DataUnit.GiB  | DataUnitConverterUtil.DataUnit.TiB | 1
        1     | DataUnitConverterUtil.DataUnit.TiB  | DataUnitConverterUtil.DataUnit.GiB | 1024
        1000  | DataUnitConverterUtil.DataUnit.B    | DataUnitConverterUtil.DataUnit.KB  | 1
        1     | DataUnitConverterUtil.DataUnit.KB   | DataUnitConverterUtil.DataUnit.B   | 1000
        1000  | DataUnitConverterUtil.DataUnit.KB   | DataUnitConverterUtil.DataUnit.MB  | 1
        1     | DataUnitConverterUtil.DataUnit.MB   | DataUnitConverterUtil.DataUnit.KB  | 1000
        1000  | DataUnitConverterUtil.DataUnit.MB   | DataUnitConverterUtil.DataUnit.GB  | 1
        1     | DataUnitConverterUtil.DataUnit.GB   | DataUnitConverterUtil.DataUnit.MB  | 1000
        1000  | DataUnitConverterUtil.DataUnit.GB   | DataUnitConverterUtil.DataUnit.TB  | 1
        1     | DataUnitConverterUtil.DataUnit.TB   | DataUnitConverterUtil.DataUnit.GB  | 1000
    }

    @Unroll
    def "dataUnitConverterBigDecimal converts #value #fromUnit to #toUnit correctly"() {
        expect:
        DataUnitConverterUtil.dataUnitConverterBigDecimal(value, fromUnit, toUnit).setScale(2, java.math.RoundingMode.HALF_UP) == expected

        where:
        value         | fromUnit                            | toUnit                             | expected
        1024.5G       | DataUnitConverterUtil.DataUnit.B    | DataUnitConverterUtil.DataUnit.KiB | 1.00G
        1.5G          | DataUnitConverterUtil.DataUnit.KiB  | DataUnitConverterUtil.DataUnit.B   | 1536.0G
        2048.5G       | DataUnitConverterUtil.DataUnit.KiB  | DataUnitConverterUtil.DataUnit.MiB | 2.00G
        1.5G          | DataUnitConverterUtil.DataUnit.MiB  | DataUnitConverterUtil.DataUnit.KiB | 1536.0G
        0.5G          | DataUnitConverterUtil.DataUnit.GiB  | DataUnitConverterUtil.DataUnit.MiB | 512.0G
        2.5G          | DataUnitConverterUtil.DataUnit.GiB  | DataUnitConverterUtil.DataUnit.TiB | 0.00G
    }

    @Unroll
    def "parseKubernetesUnitsToBytes correctly parses #input"() {
        expect:
        DataUnitConverterUtil.parseKubernetesUnitsToBytes(input) == expected

        where:
        input  | expected
        "2Gi"  | 2147483648L
        "2gi"  | 2147483648L
        "5G"   | 5000000000L
        "5g"   | 5000000000L
        "512Mi"| 536870912L
        "512mi"| 536870912L
        "100M" | 100000000L
        "100m" | 100000000L
        "1024Ki"| 1048576L
        "1024ki"| 1048576L
        "500K" | 500000L
        "500k" | 500000L
        "1024" | 1024L
        "2048" | 2048L
    }

    def "parseKubernetesUnitsToBytes handles decimal values"() {
        expect:
        DataUnitConverterUtil.parseKubernetesUnitsToBytes("1.5Gi") == 1610612736L
        DataUnitConverterUtil.parseKubernetesUnitsToBytes("2.5G") == 2500000000L
        DataUnitConverterUtil.parseKubernetesUnitsToBytes("0.5Mi") == 524288L
    }

    def "parseKubernetesUnitsToBytes returns null for null input"() {
        expect:
        DataUnitConverterUtil.parseKubernetesUnitsToBytes(null) == null
    }

    def "parseKubernetesUnitsToBytes returns null for empty string"() {
        expect:
        DataUnitConverterUtil.parseKubernetesUnitsToBytes("") == null
    }

    def "parseKubernetesUnitsToBytes returns null for invalid input"() {
        expect:
        DataUnitConverterUtil.parseKubernetesUnitsToBytes("invalid") == null
        DataUnitConverterUtil.parseKubernetesUnitsToBytes("XYZ") == null
        DataUnitConverterUtil.parseKubernetesUnitsToBytes("123ABC") == null
    }

    def "parseKubernetesUnitsToBytes handles whitespace"() {
        expect:
        DataUnitConverterUtil.parseKubernetesUnitsToBytes("  2Gi  ") == 2147483648L
        DataUnitConverterUtil.parseKubernetesUnitsToBytes("  512Mi  ") == 536870912L
    }

    @Unroll
    def "DataUnit enum has correct byte values for #unit"() {
        expect:
        unit.toBytes() == expected

        where:
        unit                                  | expected
        DataUnitConverterUtil.DataUnit.B      | 1L
        DataUnitConverterUtil.DataUnit.KiB    | 1024L
        DataUnitConverterUtil.DataUnit.MiB    | 1048576L
        DataUnitConverterUtil.DataUnit.GiB    | 1073741824L
        DataUnitConverterUtil.DataUnit.TiB    | 1099511627776L
        DataUnitConverterUtil.DataUnit.KB     | 1000L
        DataUnitConverterUtil.DataUnit.MB     | 1000000L
        DataUnitConverterUtil.DataUnit.GB     | 1000000000L
        DataUnitConverterUtil.DataUnit.TB     | 1000000000000L
    }

    def "cross-unit conversions between binary and decimal units"() {
        expect:
        // 1 GiB to GB
        DataUnitConverterUtil.dataUnitConverter(1, DataUnitConverterUtil.DataUnit.GiB, DataUnitConverterUtil.DataUnit.GB) == 1
        // 1 GB to GiB (results in 0 due to integer division)
        DataUnitConverterUtil.dataUnitConverter(1, DataUnitConverterUtil.DataUnit.GB, DataUnitConverterUtil.DataUnit.GiB) == 0
        // 2 GB to GiB
        DataUnitConverterUtil.dataUnitConverter(2, DataUnitConverterUtil.DataUnit.GB, DataUnitConverterUtil.DataUnit.GiB) == 1
    }

    def "large value conversions"() {
        expect:
        DataUnitConverterUtil.dataUnitConverter(1024, DataUnitConverterUtil.DataUnit.GiB, DataUnitConverterUtil.DataUnit.B) == 1099511627776L
        DataUnitConverterUtil.dataUnitConverter(1099511627776L, DataUnitConverterUtil.DataUnit.B, DataUnitConverterUtil.DataUnit.GiB) == 1024
    }

    def "dataUnitConverter handles zero and negative values"() {
        expect:
        DataUnitConverterUtil.dataUnitConverter(0, DataUnitConverterUtil.DataUnit.MiB, DataUnitConverterUtil.DataUnit.B) == 0
        DataUnitConverterUtil.dataUnitConverter(-1, DataUnitConverterUtil.DataUnit.GB, DataUnitConverterUtil.DataUnit.MB) == -1000
    }

    def "dataUnitConverterBigDecimal preserves precision for fractional conversions"() {
        when:
        def result = DataUnitConverterUtil.dataUnitConverterBigDecimal(1.25G, DataUnitConverterUtil.DataUnit.GiB, DataUnitConverterUtil.DataUnit.MiB)

        then:
        result == 1280.0G
    }

    def "parseKubernetesUnitsToBytes handles signed and scientific notation"() {
        expect:
        DataUnitConverterUtil.parseKubernetesUnitsToBytes("-1Gi") == -1073741824L
        DataUnitConverterUtil.parseKubernetesUnitsToBytes("1e3") == 1000L
        DataUnitConverterUtil.parseKubernetesUnitsToBytes("1E3") == 1000L
    }

    def "parseKubernetesUnitsToBytes handles unit with embedded spaces"() {
        expect:
        DataUnitConverterUtil.parseKubernetesUnitsToBytes("1 Gi") == 1073741824L
        DataUnitConverterUtil.parseKubernetesUnitsToBytes("2  Mi") == 2097152L
    }
}
