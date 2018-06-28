package com.botkul.cordapp.common.exception

import net.corda.core.CordaRuntimeException

class TooManyStatesFoundException(override val message: String) : CordaRuntimeException(message)