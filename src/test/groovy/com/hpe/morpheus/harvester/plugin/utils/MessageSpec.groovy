package com.hpe.morpheus.harvester.plugin.utils

import spock.lang.Specification

class MessageSpec extends Specification {

    def "storage volume error message constants are defined correctly"() {
        expect:
        Message.STORAGE_VOLUME_NULL == "StorageVolume is null"
        Message.STORAGE_VOLUME_DATASTORE_NULL == "Missing datastore in StorageVolume"
        Message.STORAGE_VOLUME_CREATE_FAILED == "Persistent Volume Claim creation failed"
        Message.STORAGE_VOLUME_CLONE_FAILED == "Persistent Volume Claim clone failed"
    }



    def "all message constants are accessible"() {
        expect:
        Message.STORAGE_VOLUME_NULL instanceof String
        Message.STORAGE_VOLUME_DATASTORE_NULL instanceof String
        Message.STORAGE_VOLUME_CREATE_FAILED instanceof String
        Message.STORAGE_VOLUME_CLONE_FAILED instanceof String
    }
}
