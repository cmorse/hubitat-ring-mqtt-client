/**
 *  ****************  Ring Alarm Smoke listener Driver ****************
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
 *  Changes:
 *
 *  1.0.0 - Initial release
 */

import groovy.transform.Field

def driverVersion() { return "1.0.0"; }

// Longer timeout of 3 hours for things that change less frequently
@Field static final Map fieldUpdateTimeouts = [
    "battery": 60 * 60 * 3,
    "tamper": 60 * 60 * 3,
]

// Allow update after 15 minutes
@Field static final int defaultFieldUpdateTimeout = 60 * 15

metadata {
    definition (name: "Ring Alarm Smoke Listener",
                namespace: "cdmorse",
                author: "Caleb Morse",
                importURL: "https://raw.githubusercontent.com/cmorse/hubitat-ring-mqtt-client/master/drivers/ring-alarm-smoke-listener.groovy") {
        capability "Battery"
        capability "SmokeDetector"
        capability "Tamper Alert"

        attribute "lastUpdated", "String"

        // Smoke commands
        command "detected"
        command "clear"

        /**
         * Begin commands shared across ring device drivers
         */

        // Battery commands
        command "battery", ['number']

        // Tamper commands
        command "tamperDetected"
        command "tamperClear"
    }

    preferences{
        // Standard logging options for all drivers
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false, description: ""
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true, description: ""
    }
}

def installed(){
    log.info("Ring Alarm Smoke Listener installed...")
    initialize()
}

def updated() {
    log.info("Ring Alarm Smoke Listener updated...")
    log.warn("Description logging is: ${txtEnable == true}")
    initialize()
}

def detected() { commonEventHandler("smoke", "detected") }
def clear() { commonEventHandler("smoke", "clear") }

/**
 * Begin code that is largely shared between ring device drivers
 */

def battery(java.math.BigDecimal percentage) {
    commonEventHandler("battery", percentage)
}

def tamperDetected() { commonEventHandler("tamper", "detected") }
def tamperClear() { commonEventHandler("tamper", "clear") }

def initialize() {
    //turn off debug logs after 15 minutes
    if (logEnable) {
        runIn(900, logsOff)
    }

    state.version = driverVersion()
    if (!state.containsKey("lastEventUpdate")) {
        state.lastEventUpdate = [:]
    }
}

private def commonEventHandler(String eventName, eventValue) {
    boolean isStateChange = device.currentValue(eventName) != eventValue
    boolean shouldUpdateNow = false

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
            case "battery":
                logInfo("battery is ${eventValue}%")
                break
            case "contact":
            case "moisture":
                logInfo("is ${eventValue}")
                break
            case "freeze":
                logInfo("$is ${eventValue == 'clear' ? 'above' : 'below'} freezing")
                break
            case "carbonMonoxide":
            case "motion":
            case "smoke":
            case "tamper":
                logInfo("${eventName} ${eventValue}")
                break
            default:
                logError("Changing unsupported attribute '${eventName}'")
                break
            }
        }

        sendEvent(name: eventName, value: eventValue, isStateChange: isStateChange)

        def nowDay = new Date().format("MMM dd 'at' h:mm a", location.timeZone)
        sendEvent(name: "lastUpdated", value: nowDay)
    }
}

def logsOff(){
    log.warn("Disabling debug logging")
    device.updateSetting("logEnable",[value: "false", type: "bool"])
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