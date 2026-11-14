package com.hpe.morpheus.harvester.plugin.utils

import groovy.transform.CompileStatic

@CompileStatic
class Message {
    // common constants for error messages returned to core
    public static final String STORAGE_VOLUME_NULL = "StorageVolume is null"
    public static final String STORAGE_VOLUME_DATASTORE_NULL = "Missing datastore in StorageVolume"
    public static final String STORAGE_VOLUME_CREATE_FAILED = "Persistent Volume Claim creation failed"
    public static final String STORAGE_VOLUME_CLONE_FAILED = "Persistent Volume Claim clone failed"
}