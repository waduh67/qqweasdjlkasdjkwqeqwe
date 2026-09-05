package com.duluin.ftth.collector

class CollectorRuntimeMode private constructor(val simulatorEnabled: Boolean) {
    companion object {
        fun resolve(environment: String, simulatorRequested: Boolean): CollectorRuntimeMode {
            val production = environment.trim().equals("production", ignoreCase = true) ||
                environment.trim().equals("prod", ignoreCase = true)
            require(!production || !simulatorRequested) { "SIMULATOR_FORBIDDEN_IN_PRODUCTION" }
            return CollectorRuntimeMode(simulatorRequested)
        }
    }
}
