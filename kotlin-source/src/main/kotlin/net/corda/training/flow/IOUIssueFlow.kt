package net.corda.training.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.training.contract.IOUContract
import net.corda.training.state.IOUState
import kotlin.math.sign

/**
 * This is the flow which handles issuance of new IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled by the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUIssueFlow(val state: IOUState) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        //sign and verify
        val notary = this.serviceHub.networkMapCache.notaryIdentities.get(0)
        val builder = TransactionBuilder(notary = notary)
        val command = Command(IOUContract.Commands.Issue(), state.participants.map { x -> x.owningKey })
        builder.addOutputState(state)
        builder.addCommand(command)
        builder.verify(this.serviceHub)
        val signedTransaction = this.serviceHub.signInitialTransaction(builder)

        //collecting other parties signature(s)
        val flowSessions = state.participants.minus(this.ourIdentity).map { p -> initiateFlow(p) }.toSet()
        val fullySignedTransaction = subFlow(CollectSignaturesFlow(signedTransaction, flowSessions))

        //storing transaction to all parties vaults
        val finalFlow = FinalityFlow(fullySignedTransaction, flowSessions)
        return subFlow(finalFlow)
    }
}

/**
 * This is the flow which signs IOU issuances.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUIssueFlow::class)
class IOUIssueFlowResponder(val flowSession: FlowSession): FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction" using (output is IOUState)
            }
        }
        val signedTx: SignedTransaction = subFlow(signedTransactionFlow)
        subFlow(ReceiveFinalityFlow(flowSession, signedTx.id))
    }
}