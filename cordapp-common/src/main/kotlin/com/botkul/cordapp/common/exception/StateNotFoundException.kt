package com.botkul.cordapp.common.exception

import net.corda.core.CordaRuntimeException

class StateNotFoundException(override val message: String) : CordaRuntimeException(message)