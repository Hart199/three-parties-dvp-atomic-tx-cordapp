package com.botkul.cordapp.clearinghouse.flows

import co.paralleluniverse.fibers.Suspendable
import com.botkul.cordapp.common.flows.IdentitySyncFlow
import com.botkul.cordapp.common.flows.SignTxFlow
import com.botkul.cordapp.flows.AbstractConfirmAssetTransferRequestFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.transactions.SignedTransaction

@InitiatedBy(AbstractConfirmAssetTransferRequestFlow::class)
class ConfirmAssetTransferRequestResponderFlow(private val otherSideSession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        //Identity sync flow.
        subFlow(IdentitySyncFlow.Receive(otherSideSession))
        //Transaction verification and signing.
        val stx = subFlow(SignTxFlow(otherSideSession))
        return waitForLedgerCommit(stx.id)
    }
}
