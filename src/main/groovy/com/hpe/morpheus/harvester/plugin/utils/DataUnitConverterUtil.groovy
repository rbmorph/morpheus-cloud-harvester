package com.hpe.morpheus.harvester.plugin.utils

import groovy.util.logging.Slf4j

@Slf4j
class DataUnitConverterUtil {
    static enum DataUnit {
        B(1),        // Byte
        KiB(1024),   // Kibibyte (1 KiB = 1024 B)
        MiB(1024 * 1024), // Mebibyte (1 MiB = 1024 KiB = 1024^2 B)
        GiB(1024 * 1024 * 1024), // Gibibyte (1 GiB = 1024 MiB = 1024^3 B)
        TiB(1024L * 1024L * 1024L * 1024L), // Tebibyte (1 TiB = 1024 GiB = 1024^4 B)

        // Decimal units (base 10) - commonly used in storage contexts
        KB(1000),    // Kilobyte (1 KB = 1000 B)
        MB(1000 * 1000), // Megabyte (1 MB = 1000 KB = 1000^2 B)
        GB(1000 * 1000 * 1000), // Gigabyte (1 GB = 1000 MB = 1000^3 B)
        TB(1000L * 1000L * 1000L * 1000L), // Terabyte (1 TB = 1000 GB = 1000^4 B)

        private final long bytes

        DataUnit(long bytes) {
            this.bytes = bytes
        }

        // Method to convert to bytes
        long toBytes() {
            return this.bytes
        }
    }

    static long dataUnitConverter(long value, DataUnit fromUnit, DataUnit toUnit) {
        return value * fromUnit.toBytes() / toUnit.toBytes() as long
    }

    static BigDecimal dataUnitConverterBigDecimal(BigDecimal value, DataUnit fromUnit, DataUnit toUnit) {
        return value * fromUnit.toBytes() / toUnit.toBytes() as BigDecimal
    }

    /**
     * Parse Kubernetes-style memory/storage strings to bytes
     * @param unitString The string to parse (e.g., "2Gi", "5M")
     * @return Long value in bytes, or null if parsing fails
     */
    static Long parseKubernetesUnitsToBytes(String unitString) {
        if (!unitString) {
            return null
        }

        try {
            String normalized = unitString.toLowerCase().trim()

            // Handle each Kubernetes unit suffix
            if (normalized.endsWith('gi')) {
                double value = Double.parseDouble(normalized.replace('gi', ''))
                return (value * DataUnit.GiB.toBytes()).longValue()
            } else if (normalized.endsWith('g')) {
                double value = Double.parseDouble(normalized.replace('g', ''))
                return (value * DataUnit.GB.toBytes()).longValue()
            } else if (normalized.endsWith('mi')) {
                double value = Double.parseDouble(normalized.replace('mi', ''))
                return (value * DataUnit.MiB.toBytes()).longValue()
            } else if (normalized.endsWith('m')) {
                double value = Double.parseDouble(normalized.replace('m', ''))
                return (value * DataUnit.MB.toBytes()).longValue()
            } else if (normalized.endsWith('ki')) {
                double value = Double.parseDouble(normalized.replace('ki', ''))
                return (value * DataUnit.KiB.toBytes()).longValue()
            } else if (normalized.endsWith('k')) {
                double value = Double.parseDouble(normalized.replace('k', ''))
                return (value * DataUnit.KB.toBytes()).longValue()
            } else {
                // Assume it's already in bytes
                return Double.parseDouble(normalized).longValue()
            }
        } catch (Exception e) {
            log.warn("Failed to parse Kubernetes unit string: ${unitString}", e.message)
            return null
        }
    }
}
