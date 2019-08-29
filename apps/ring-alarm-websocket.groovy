/**
 *  ****************  Ring Alarm Websocket  ****************
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


// Scheduled times that ecobee goes into a typically 'home' mode
// @note Must have same days of week as scheduledAwayTimes
@Field static final Map scheduledHomeTimes = [
    "Monday": "22:00",
    "Tuesday": "22:00",
    "Wednesday": "22:00",
    "Thursday": "22:00",
]

def driverVersion() { return "1.1.0" }

definition(
    name: "Ring Alarm Websocket",
    namespace: "cdmorse",
    author: "Caleb Morse",
    description: "Who knows?",
    category: "Convenience",
    oauth: true,
    importURL: "https://raw.githubusercontent.com/cmorse/hubitat-my-ecobee-helper/master/my-ecobee-helper.groovy",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png"
)

preferences {
    page(name: "main")
}

def main() {
    String defaultLabel = "Ring Alarm Websocket"

    dynamicPage(name: "main", title: defaultLabel, uninstall: true, install: true){
        section("") {
            paragraph("When moving to an 'Away' state, the hubitat mode will be manually set to " +
                      "the user-selected hubitat away mode, rather than relying on ecobee to do " + 
                      "this. For all other states, ecobee will handle things.")
        
            app.updateLabel(defaultLabel)
            state.appDisplayName = defaultLabel

            if(settings.tempDisable) {
                paragraph "WARNING: Temporarily Paused - re-enable below."

                def newLabel = state.appDisplayName + '<span style="color:red"> (paused)</span>'
                if (app.label != newLabel) {
                    app.updateLabel(newLabel)
                }
            } else {
                if (app.label != state.appDisplayName) {
                    app.updateLabel(state.appDisplayName)
                }

                input name: "username", type: "text", title: "Ring Username:", description: "", displayDuringSetup: true
                input name: "password", type: "password", title: "Ring Password:", description: "", displayDuringSetup: true
                
            }
        }

        if(!settings.tempDisable) {
            section("<b>Advanced</b>") {
                input "logEnable", "bool", title: "Enable debug logging", defaultValue: false
                input "txtEnable", "bool", title: "Enable descriptionText logging", defaultValue: true
            }
        }

        section(title: "Temporary Pause") {
            input(name: "tempDisable", title: "Pause this Helper? ", type: "bool", submitOnChange: true)
        }
    }
}

def installed() {
    log.info("Ring Alarm Websocket installed")
    initialize()
}

def uninstalled() {
    log.info("Ring Alarm Websocket uninstalled")
    unsubscribe()
}

def updated() {
    log.info("Ring Alarm Websocket updated")
    initialize()
}

def initialize() {
    log.info("Ring Alarm Websocket initialize....")

    state.version = driverVersion()
}

def runTests() {
    log.info("Running unit tests...")
    try {
        
    } catch (AssertionError e) {
        log.error("Unit tests failed to pass ${e}")
    }
    log.info("Finished running unit tests...")
}

def logInfo(msg) {
	if (txtEnable) {
        log.info(msg)
    }
}

def logDebug(msg){
	if(logEnable) {
        log.debug(msg)
    }
}

def logWarn(msg){
    log.warn(msg)
}

def logError(msg){
    log.error(msg)
}