package com.botkul.cordapp.common.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.ContractState
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap

object IdentitySyncFlow {
    /**
     * Flow for ensuring that one or more counterparties to a transaction have the full certificate paths of confidential
     * identities used in the transaction. This is intended for use as a subflow of another flow, typically between
     * transaction assembly and signing. An example of where this is useful is where a recipient of a [Cash] state wants
     * to know that it is being paid by the correct party, and the owner of the state is a confidential identity of that
     * party. This flow would send a copy of the confidential identity path to the recipient, enabling them to verify that
     * identity.
     *
     * @return a mapping of well known identities to the confidential identities used in the transaction.
     */
    class Send(val otherSideSessions: Set<FlowSession>,
               val tx: WireTransaction,
               override val progressTracker: ProgressTracker) : FlowLogic<Unit>() {
        constructor(otherSide: FlowSession, tx: WireTransaction) : this(setOf(otherSide), tx, tracker())

        companion object {
            object SYNCING_IDENTITIES : ProgressTracker.Step("Syncing identities")

            fun tracker() = ProgressTracker(SYNCING_IDENTITIES)
        }

        @Suspendable
        override fun call() {
            progressTracker.currentStep = SYNCING_IDENTITIES
            val states: List<ContractState> = (tx.inputs.map { serviceHub.loadState(it) }.requireNoNulls().map { it.data } + tx.outputs.map { it.data })

            val identities: Set<AbstractParty> = states.flatMap { it.participants }.toSet()
            // Filter participants down to the set of those not in the network map (are not well known)
            val confidentialIdentities = identities
                    .filter { serviceHub.networkMapCache.getNodesByLegalIdentityKey(it.owningKey).isEmpty() }
                    .toList()

            val identityCertificates: Map<AbstractParty, PartyAndCertificate?> = identities
                    .map { Pair(it, serviceHub.identityService.certificateFromKey(it.owningKey)) }.toMap()

            otherSideSessions.forEach { otherSideSession ->
                val requestedIdentities: List<AbstractParty> = otherSideSession.sendAndReceive<List<AbstractParty>>(confidentialIdentities).unwrap { req ->
                    require(req.all { it in identityCertificates.keys }) { "${otherSideSession.counterparty} requested a confidential identity not part of transaction: ${tx.id}" }
                    req
                }

                val sendIdentities: List<PartyAndCertificate?> = requestedIdentities.map {
                    val identityCertificate = identityCertificates[it]
                    if (identityCertificate != null)
                        identityCertificate
                    else
                        throw IllegalStateException("Counterparty requested a confidential identity for which we do not have the certificate path: ${tx.id}")
                }
                otherSideSession.send(sendIdentities)
            }
        }

    }

    /**
     * Handle an offer to provide proof of identity (in the form of certificate paths) for confidential identities which
     * we do not yet know about.
     */
    class Receive(val otherSideSession: FlowSession) : FlowLogic<Unit>() {
        companion object {
            object RECEIVING_IDENTITIES : ProgressTracker.Step("Receiving confidential identities")
            object RECEIVING_CERTIFICATES : ProgressTracker.Step("Receiving certificates for unknown identities")
        }

        override val progressTracker: ProgressTracker = ProgressTracker(RECEIVING_IDENTITIES, RECEIVING_CERTIFICATES)

        @Suspendable
        override fun call() {
            progressTracker.currentStep = RECEIVING_IDENTITIES
            val allIdentities = otherSideSession.receive<List<AbstractParty>>().unwrap { it }
            val unknownIdentities = allIdentities.filter { serviceHub.identityService.wellKnownPartyFromAnonymous(it) == null }

            progressTracker.currentStep = RECEIVING_CERTIFICATES
            val missingIdentities = otherSideSession.sendAndReceive<List<PartyAndCertificate>>(unknownIdentities)

            // Batch verify the identities we've received, so we know they're all correct before we start storing them in
            // the identity service
            missingIdentities.unwrap { identities ->
                identities.forEach { it.verify(serviceHub.identityService.trustAnchor) }
                identities
            }.forEach { identity ->
                // Store the received confidential identities in the identity service so we have a record of which well known identity they map to.
                serviceHub.identityService.verifyAndRegisterIdentity(identity)
            }
        }
    }
}
