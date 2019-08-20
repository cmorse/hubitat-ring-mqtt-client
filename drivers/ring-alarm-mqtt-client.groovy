/**
 *  ****************  Ring Alarm MQTT Client  ****************
 *
 *  Design Usage:
 *  This driver receives events from the ring-mqtt-client nodejs implementation
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 * Copyright (c) 2019 Caleb Morse
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 *  Known limitations
 *  * Unable to remove devices that no longer exist
 *  * User cannot select which devices are included
 *  * Manual refresh is not available
 *
 *  Changes:
 *
 *  1.0.0 - Initial release
 */

import groovy.transform.Field

def driverVersion() { return "1.0.0"; }

@Field static final List supportedDevices = ["cold", "door", "moisture", "motion", "gas", "smoke"]

// Longer timeout of 3 hours for things that change less frequently
@Field static final Map fieldUpdateTimeouts = [
    "battery": 60 * 60 * 3,
    "tamper": 60 * 60 * 3,
]

// Allow update after 15 minutes
@Field static final int defaultFieldUpdateTimeout = 60 * 15

@Field static final String carbonMonoxideListenerDriverName = "Ring Alarm Carbon Monoxide Listener"
@Field static final String contactSensorDriverName = "Ring Alarm Contact Sensor"
@Field static final String freezeSensorDriverName = "Ring Alarm Freeze Sensor"
@Field static final String moistureSensorDriverName = "Ring Alarm Moisture Sensor"
@Field static final String motionSensorDriverName = "Ring Alarm Motion Sensor"
@Field static final String smokeListenerDriverName = "Ring Alarm Smoke Listener"

metadata {
    definition (name: "Ring Alarm MQTT Client",
                namespace: "cdmorse",
                author: "Caleb Morse",
                importURL: "https://raw.githubusercontent.com/cmorse/hubitat-ring-mqtt-client/master/drivers/ring-alarm-mqtt-client.groovy") {
        capability "Initialize"
        capability "Tamper Alert"
        attribute "alarmState", "string"
        attribute 'tamper', 'enum', ['clear', 'detected']

        attribute "lastUpdated", "String"

        // Tamper commands
        command "tamperDetected"
        command "tamperClear"

        command "removeAllChildren", [] // Deletes all child devices
        command "runTests", []          // Runs unit tests
    }

    preferences {
        input name: "MQTTBroker", type: "text", title: "MQTT Broker Address:", required: true, displayDuringSetup: true
        input name: "username", type: "text", title: "MQTT Username:", description: "(blank if none)", displayDuringSetup: true
        input name: "password", type: "password", title: "MQTT Password:", description: "(blank if none)", displayDuringSetup: true
        input name: "locationId", type: "text", title: "Ring location ID:", displayDuringSetup: true
        input name: "topicPub", type: "text", title: "Topic to Publish to at startup", description: "", required: true, displayDuringSetup: true, defaultValue: "hass/status"
        input name: "controlHsm", type: "bool", title: "Apply changes to ring alarm to Hubitat Safety Monitor?", defaultValue: false
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false, description: ""
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true, description: ""
        input name: "testEnable", type: "bool", title: "Enable running unit tests", defaultValue: false, description: ""
    }
}

def getUniqueIdFromTopic(String topic) {
    String[] topicSplit = topic.split("/")

    String selection = topicSplit[topicSplit.length - 2]

    if (selection == "cold" || selection == "gas" || selection == "moisture" || selection == "smoke") {
        return topicSplit[topicSplit.length - 3] + "_${selection}"
    }

    return selection
}

def stripMultiSensorPortionFromUniqueId(String uniqueId) {
    for(suffix in ["_cold", "_gas", "_moisture", "_smoke"]) {
        if (uniqueId.endsWith(suffix)) {
            return uniqueId.substring(0, uniqueId.length() - suffix.length())
        }
    }
    return uniqueId
}

def installed() {
    logInfo("Ring MQTT client installed...")
}

// Parse incoming device messages to generate events
def parse(String description) {
    def parsed = interfaces.mqtt.parseMessage(description)
    String topic = parsed.topic

    if (topic.startsWith("homeassistant/")) {
        handleDiscoveryMessage(topic, parsed.payload)
    } else if (topic.endsWith("/state")) {
        handleStateMessage(topic, parsed.payload)
    } else if (topic.endsWith("/attributes")) {
        handleAttributeMessage(topic, parsed.payload)
    } else {
        logWarn("Received unsupported topic ${topic}")
    }
}

def handleDiscoveryMessage(topic, payload) {
    def payloadJson = parseJson(payload)

    String uniqueId = "ring_alarm:" + payloadJson.unique_id

    if (state.ignoredDevices.containsKey(uniqueId)) {
        return
    }

    if (settings.locationId && !payloadJson.state_topic.startsWith("ring/${settings.locationId}")) {
        logInfo("Excluding unique id ${uniqueId} because it is not in location id ${settings.locationId}")
        state.ignoredDevices[uniqueId] = true
        return
    }

    if (!supportedDevices.contains(payloadJson.device_class)) {
        if (payloadJson.name != "Ring Alarm") {
            logWarn("Received config for device that isn't in '${supportedDevices}': ${payload}")
            state.ignoredDevices[uniqueId] = true
            return
        }
    }

    // The ring alarm itself is odd and doesn't have a `device_class` field. It's name, does
    // appear to be hardcoded
    if (payloadJson.name == "Ring Alarm") {
        if (state.ringAlarmUniqueId != null) {
            logDebug("Not configuring device ${uniqueId}, already configured......")
            return
        }
        state.ringAlarmUniqueId = uniqueId
    } else {
        def childDevice = getChildDevice(uniqueId)

        if (childDevice) {
            if (state.sensors.containsKey(childDevice.id)) {
                logDebug("Not configuring device ${uniqueId}, already configured")
                return
            }
        } else {
            Map properties = ["name": payloadJson.name]

            switch(payloadJson.device_class) {
            case "cold":
                childDevice = addChildDevice(freezeSensorDriverName, uniqueId, properties)
                break
            case "door":
                childDevice = addChildDevice(contactSensorDriverName, uniqueId, properties)
                break
            case "gas":
                childDevice = addChildDevice(carbonMonoxideListenerDriverName, uniqueId, properties)
                break
            case "moisture":
                childDevice = addChildDevice(moistureSensorDriverName, uniqueId, properties)
                break
            case "motion":
                childDevice = addChildDevice(motionSensorDriverName, uniqueId, properties)
                break
            case "smoke":
                childDevice = addChildDevice(smokeListenerDriverName, uniqueId, properties)
                break
            default:
                logError("Unsupported device class ${payloadJson.device_class}")
                break
            }

            logInfo("Created child device for ${payloadJson.name}")
        }

        if (childDevice != null) {
            state.sensors[childDevice.id] = uniqueId
        }
    }

    subscribeToTopic(payloadJson.state_topic)
    subscribeToTopic(payloadJson.json_attributes_topic)
}

def subscribeToTopic(topic) {
    logDebug("Subscribing to topic ${topic}")
    state.subscribedTopics.add(topic)
    interfaces.mqtt.subscribe(topic)
}

def handleStateMessage(topic, payload) {
    String uniqueId = "ring_alarm:" + getUniqueIdFromTopic(topic)

    if (state.ringAlarmUniqueId == uniqueId) {
        logDebug("Received msg for Ring Alarm: ${payload} " + location.hsmStatus)

        def armValue = null
        boolean isStateChange = null

        switch(payload) {
        case "armed_away":
            eventValue = "armAway"
            isStateChange = location.hsmStatus != "armedAway"
            break
        case "armed_home":
            eventValue = "armHome"
            isStateChange = location.hsmStatus != "armedHome"
            break
        case "disarmed":
            eventValue = "disarm"
            isStateChange = location.hsmStatus != "disarmed"
            break
        default:
            logError("Unexpected ring alarm state: ${payload}")
            return
        }

        if (isStateChange) {
            if (controlHsm) {
                logInfo("Ring alarm is '${payload}', hsm is ${location.hsmStatus}, setting hsm to '${eventValue}'")
                sendLocationEvent(name: "hsmSetArm", value: eventValue)
            }
        }
        commonEventHandler("alarmState", eventValue, isStateChange)

    } else {
        def childDevice = getChildDevice(uniqueId)

        if (!childDevice) {
            logWarn("Can't update state because child device doesn't exist for ${uniqueId}!")
            logDebug("Payload was ${payload}, ${topic}")
            return
        }

        switch (childDevice.typeName) {
        case contactSensorDriverName:
            payload == "OFF"  ? childDevice.closed() : childDevice.open()
            break
        case motionSensorDriverName:
            payload == "OFF" ? childDevice.inactive() : childDevice.active()
            break
        case moistureSensorDriverName:
            payload == "OFF" ? childDevice.dry() : childDevice.wet()
            break
        case freezeSensorDriverName:
            payload == "OFF" ? childDevice.clear() : childDevice.detected()
            break
        case carbonMonoxideListenerDriverName:
        case smokeListenerDriverName:
            payload == "OFF" ? childDevice.clear() : childDevice.detected()
            break
        default:
            logError("Unsupported device with name ${childDevice.name}: ${payload}")
            break
        }
    }
}

def handleAttributeMessage(topic, payload) {
    String uniqueId = "ring_alarm:" + getUniqueIdFromTopic(topic)

    def targetDevices = []

    if (state.ringAlarmUniqueId == uniqueId) {
        targetDevices = [device]
    } else {
        tmpDevice = getChildDevice(uniqueId)

        if (!tmpDevice) {
            // Some events need to go to multiple devices because Ring handles them as combination devices
            for (suffix in ["cold", "gas", "moisture", "smoke"]) {
                tmpDevice = getChildDevice("${uniqueId}_${suffix}")

                if (tmpDevice) {
                    targetDevices += tmpDevice
                }
            }

            if (targetDevices.isEmpty()) {
                logWarn("Can't handle attribute update because child device doesn't exist for ${uniqueId}!")
                logDebug("Payload was ${payload}, ${topic}")
                return
            }
        } else {
            targetDevices = [tmpDevice]
        }
    }

    logDebug("Received attribute message on topic ${topic} with payload ${payload}")

    def payloadJson = parseJson(payload)

    // Some events need to go to multiple devices because Ring handles them as combination devices
    for (targetDevice in targetDevices) {
        Integer expectedKeyCount = 0

        if (payloadJson?.get('battery_level')) {
            targetDevice.battery(payloadJson.battery_level)
            expectedKeyCount++
        }

        if (payloadJson?.get('tamper_status')) {
            payloadJson.tamper_status == "ok" ? targetDevice.tamperClear() : targetDevice.tamperDetected()
            expectedKeyCount++
        }

        if (payloadJson.size() != expectedKeyCount) {
            logWarn("Unsupported attributes found in payload for ${uniqueId}: ${payload}")
        }
    }
}

private def commonEventHandler(String eventName, eventValue, isStateChangeOverride = null) {
    boolean isStateChange = device.currentValue(eventName) != eventValue
    boolean shouldUpdateNow = false

    if (isStateChangeOverride != null) {
        isStateChange = isStateChangeOverride
    }


	// Always update on state change, or when state hasn't previously been set
    if (isStateChange || !state.lastEventUpdate?.get(eventName)) {
        shouldUpdateNow = true
    } else {
        // Seconds since last update
        def seconds = Math.floor((new Date().time - state.lastEventUpdate[eventName]) / 1000)

        // Get how long to disallow field updates
        def timeout = fieldUpdateTimeouts.get(eventName, defaultFieldUpdateTimeout)

        logDebug("'${eventName}': ${seconds}s since last update, timeout is ${timeout}")

        if (seconds >= timeout) {
            shouldUpdateNow = true
        }
    }

    if (shouldUpdateNow) {
        state.lastEventUpdate[eventName] = new Date().time

        if((txtEnable && isStateChange) || logEnable) {
            switch (eventName) {
            case "tamper":
                logInfo("${eventName} ${eventValue}")
                break
            default:
                logInfo("Changing attribute '${eventName}' to '${eventValue}', isStateChange (${isStateChange == true})")
                break
            }
        }

        sendEvent(name: eventName, value: eventValue, isStateChange: isStateChange)

        def nowDay = new Date().format("MMM dd 'at' h:mm a", location.timeZone)
        sendEvent(name: "lastUpdated", value: nowDay)
    }
}

def updated() {
    logInfo("Ring MQTT client updated...")
    initialize()
}

def uninstalled() {
    logInfo("Ring MQTT client disconnecting from broker")

    interfaces.mqtt.publish(settings.topicPub, "offline")
    interfaces.mqtt.disconnect()

    device.removeAllChildren()
}

def removeAllChildren() {
    getAllChildDevices().each {
        deleteChildDevice(it.deviceNetworkId)
    }
}

def initialize() {
    log.info("Ring Alarm MQTT client Sensor updated...")
    log.warn("Description logging is: ${txtEnable == true}")

    if (logEnable) {
        // turn off debug logs after 15 minutes
        runIn(900, logsOff)
    }

    state.ringAlarmUniqueId = null
    state.sensors = [:]
    state.ignoredDevices = [:]
    state.subscribedTopics = []

    if (!state.containsKey("lastEventUpdate")) {
        state.lastEventUpdate = [:]
    }

    try {
        state.version = driverVersion()

        if (testEnable) {
           runTests()
        }

        // Open connection
        mqttbroker = "tcp://" + settings?.MQTTBroker + ":1883"
        interfaces.mqtt.connect(mqttbroker, "hubitat", settings?.username, settings?.password)
        // Give it a chance to start
        pauseExecution(500)
        logInfo("MQTT Connection established")

        interfaces.mqtt.subscribe("homeassistant/#")

        logDebug("Telling ring alarm MQTT that we just started up")
        interfaces.mqtt.publish(settings.topicPub, "online")
    } catch(e) {
        logError("Error initializing Ring MQTT client: ${e.message}")
    }
}

def tamperDetected() { commonEventHandler("tamper", "detected") }
def tamperClear() { commonEventHandler("tamper", "clear") }

def mqttClientStatus(String status){
    logWarn("MQTTStatus: ${status}")
}

def runTests() {
    log.info("Running unit tests...")
    try {
        String topicPrefix = "ring/asdf/alarm/binary_sensor"
        String fakeUniqueId = "a0a8a7a3-e8e7-42e6-8660-38c242266e80"

        assert fakeUniqueId == getUniqueIdFromTopic("${topicPrefix}/${fakeUniqueId}/state")

        for(testVal in ["cold", "gas", "moisture", "smoke"]) {
            assert "${fakeUniqueId}_${testVal}" == getUniqueIdFromTopic("${topicPrefix}/${fakeUniqueId}/${testVal}/state")
        }
    } catch (AssertionError e) {
        log.error("Unit tests failed to pass ${e}")
    }
    log.info("Finished running unit tests...")
}

def logsOff(){
    logWarn("Disabling debug logging")
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

def logInfo(msg) {
	if (txtEnable) {
        log.info("${device.label ?: device.name}: ${msg}")
    }
}

def logDebug(msg){
	if(logEnable) {
        log.debug("${device.label ?: device.name}: ${msg}")
    }
}

def logWarn(msg){
    log.warn("${device.label ?: device.name}: ${msg}")
}

def logError(msg){
    log.error("${device.label ?: device.name}: ${msg}")
}