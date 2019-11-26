package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.IOUContract
import com.example.flow.ExampleFlow.Acceptor
import com.example.flow.ExampleFlow.Initiator
import com.example.state.IOUState
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step
import net.corda.core.contracts.*
import java.util.*
import net.corda.core.contracts.StateAndContract







/**
 * This flow allows two parties (the [Initiator] and the [Acceptor]) to come to an agreement about the IOU encapsulated
 * within an [IOUState].
 *
 * In our simple example, the [Acceptor] always accepts a valid IOU.
 *
 * These flows have deliberately been implemented by using only the call() method for ease of understanding. In
 * practice we would recommend splitting up the various stages of the flow into sub-routines.
 *
 * All methods called within the [FlowLogic] sub-class need to be annotated with the @Suspendable annotation.
 */
object ExampleFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val otherParty: Party ,val amount: Double,val tradeDate: Date,val status: String) : FlowLogic<SignedTransaction>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : Step("Generating transaction based on new IOU.")
            object VERIFYING_TRANSACTION : Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : Step("Signing transaction with our private key.")
            object GATHERING_SIGS : Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    GENERATING_TRANSACTION,
                    VERIFYING_TRANSACTION,
                    SIGNING_TRANSACTION,
                    GATHERING_SIGS,
                    FINALISING_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): SignedTransaction {
            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryIdentities[0]

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION
            // Generate an unsigned transaction.
            val iouState = IOUState(serviceHub.myInfo.legalIdentities.first() , otherParty, UniqueIdentifier(), amount ,tradeDate,status)
            val txCommand = Command(IOUContract.Commands.Create(), iouState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addOutputState(iouState, IOUContract.ID)
                    .addCommand(txCommand)

            // Stage 2.
            progressTracker.currentStep = VERIFYING_TRANSACTION
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION
            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGS
            // Send the state to the counterparty, and receive it back with their signature.
            val otherPartySession = initiateFlow(otherParty)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(otherPartySession), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            val notarisedTransactionOne = subFlow(FinalityFlow(fullySignedTx, setOf(otherPartySession), FINALISING_TRANSACTION.childProgressTracker()))


            // Start update flow to set status as recieved
            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION
            val transactionOneRef = StateRef(notarisedTransactionOne.id, 0)
            val transactionOneStateAndRef = serviceHub.toStateAndRef<ContractState>(transactionOneRef)

            val updatedState = IOUState(serviceHub.myInfo.legalIdentities.first() , otherParty, UniqueIdentifier(), amount ,tradeDate,"Recieved")
            val state_contract = StateAndContract(updatedState, "com.example.contract.IOUContract")
            val command_to_update_status = Command(IOUContract.Commands.Create(), updatedState.participants.map { it.owningKey })

            val updateTransactionBuilder = TransactionBuilder(notary)
                    .addInputState(transactionOneStateAndRef)
                    .withItems(state_contract, command_to_update_status);

            // Stage 2.
            progressTracker.currentStep = VERIFYING_TRANSACTION
            // Verify that the update transaction is valid.
            updateTransactionBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION
            val updateSignedTx = serviceHub.signInitialTransaction(updateTransactionBuilder)

            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGS
            // Send the state to the counterparty, and receive it back with their signature.
            val otherPartySessionTwo = initiateFlow(otherParty)
            // Send the state to the counterparty, and receive it back with their signature.
            val updateFinalSignedTx = subFlow(CollectSignaturesFlow(updateSignedTx, setOf(otherPartySessionTwo), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            return subFlow(FinalityFlow(updateFinalSignedTx, setOf(otherPartySession), FINALISING_TRANSACTION.childProgressTracker()))

        }
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
                    "This must be an IOU transaction." using (output is IOUState)

                }
            }
            val txId = subFlow(signTransactionFlow).id

            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }
}