// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.ui

/**
 * Minimal YAML writer for displaying Kubernetes objects (parsed JSON: maps, lists, strings, numbers, booleans, null),
 * in the layout kubectl and Harvester use (list items at the same indent as their key, multi-line strings as | blocks).
 */
class YamlWriter {

    static String toYaml(def value) {
        StringBuilder sb = new StringBuilder()
        if (value instanceof Map) {
            writeMap(sb, value as Map, 0)
        } else if (value instanceof List) {
            writeList(sb, value as List, 0)
        } else {
            sb.append(scalar(value)).append('\n')
        }
        return sb.toString()
    }

    private static void writeMap(StringBuilder sb, Map map, int indent) {
        String pad = ' ' * indent
        map.each { k, v ->
            String key = scalar(k?.toString())
            if (v instanceof Map) {
                if (v.isEmpty()) {
                    sb.append(pad).append(key).append(': {}\n')
                } else {
                    sb.append(pad).append(key).append(':\n')
                    writeMap(sb, v as Map, indent + 2)
                }
            } else if (v instanceof List) {
                if (v.isEmpty()) {
                    sb.append(pad).append(key).append(': []\n')
                } else {
                    sb.append(pad).append(key).append(':\n')
                    writeList(sb, v as List, indent)
                }
            } else if (v instanceof CharSequence && isBlock(v.toString())) {
                writeBlock(sb, pad + key + ': ', v.toString(), indent + 2)
            } else {
                sb.append(pad).append(key).append(': ').append(scalar(v)).append('\n')
            }
        }
    }

    private static void writeList(StringBuilder sb, List list, int indent) {
        String pad = ' ' * indent
        list.each { item ->
            if ((item instanceof Map && !item.isEmpty()) || (item instanceof List && !item.isEmpty())) {
                StringBuilder inner = new StringBuilder()
                if (item instanceof Map) {
                    writeMap(inner, item as Map, indent + 2)
                } else {
                    writeList(inner, item as List, indent + 2)
                }
                // replace the first line's indent with "- "
                sb.append(pad).append('- ').append(inner.substring(indent + 2))
            } else if (item instanceof Map) {
                sb.append(pad).append('- {}\n')
            } else if (item instanceof List) {
                sb.append(pad).append('- []\n')
            } else if (item instanceof CharSequence && isBlock(item.toString())) {
                writeBlock(sb, pad + '- ', item.toString(), indent + 2)
            } else {
                sb.append(pad).append('- ').append(scalar(item)).append('\n')
            }
        }
    }

    private static boolean isBlock(String s) {
        // a | block can't start with a space or hold carriage returns/tabs reliably: those stay quoted
        if (!s.contains('\n') || s.contains('\r') || s.contains('\t')) return false
        String firstLine = s.split('\n').find { !it.isEmpty() }
        return firstLine != null && !firstLine.startsWith(' ')
    }

    private static void writeBlock(StringBuilder sb, String prefix, String s, int indent) {
        String pad = ' ' * indent
        boolean trailingNewline = s.endsWith('\n')
        String body = trailingNewline ? s.substring(0, s.length() - 1) : s
        String indicator = trailingNewline ? (body.endsWith('\n') ? '|+' : '|') : '|-'
        sb.append(prefix).append(indicator).append('\n')
        body.split('\n', -1).each { line ->
            if (line.isEmpty()) {
                sb.append('\n')
            } else {
                sb.append(pad).append(line).append('\n')
            }
        }
    }

    static String scalar(def v) {
        if (v == null) return 'null'
        if (v instanceof Boolean || v instanceof Number) return v.toString()
        String s = v.toString()
        return needsQuotes(s) ? quote(s) : s
    }

    private static final List<String> RESERVED = ['true', 'false', 'yes', 'no', 'on', 'off', 'null', '~', 'y', 'n']

    static boolean needsQuotes(String s) {
        if (s.isEmpty()) return true
        if (RESERVED.contains(s.toLowerCase())) return true
        if (s ==~ /[-+]?(\.[0-9]+|[0-9]+(\.[0-9]*)?)([eE][-+]?[0-9]+)?/ || s ==~ /0x[0-9a-fA-F]+/ || s ==~ /0o?[0-7]+/) return true
        if (s ==~ /\.(inf|Inf|INF|nan|NaN|NAN)/ || s ==~ /[-+]\.(inf|Inf|INF)/) return true
        if ('-?:,[]{}#&*!|>\'"%@`'.indexOf(s.charAt(0) as int) >= 0) return true
        if (s.startsWith(' ') || s.endsWith(' ')) return true
        if (s.contains(': ') || s.contains(' #') || s.endsWith(':')) return true
        for (char ch : s.toCharArray()) {
            if (ch < (' ' as char)) return true
        }
        return false
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder('"')
        for (char ch : s.toCharArray()) {
            if (ch == ('\\' as char)) {
                sb.append('\\\\')
            } else if (ch == ('"' as char)) {
                sb.append('\\"')
            } else if (ch == ('\n' as char)) {
                sb.append('\\n')
            } else if (ch == ('\r' as char)) {
                sb.append('\\r')
            } else if (ch == ('\t' as char)) {
                sb.append('\\t')
            } else if (ch < (' ' as char)) {
                sb.append(String.format('\\x%02x', (int) ch))
            } else {
                sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}
