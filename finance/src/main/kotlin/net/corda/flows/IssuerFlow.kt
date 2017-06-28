package net.corda.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.contracts.asset.Cash
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.OpaqueBytes
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import java.util.*

/**
 *  This flow enables a client to request issuance of some [FungibleAsset] from a
 *  server acting as an issuer (see [Issued]) of FungibleAssets.
 *
 *  It is not intended for production usage, but rather for experimentation and testing purposes where it may be
 *  useful for creation of fake assets.
 */
object IssuerFlow {
    @CordaSerializable
    data class IssuanceRequestState(val amount: Amount<Currency>,
                                    val issueToParty: Party,
                                    val issuerPartyRef: OpaqueBytes,
                                    val notaryParty: Party,
                                    val anonymous: Boolean)

    /**
     * IssuanceRequester should be used by a client to ask a remote node to issue some [FungibleAsset] with the given details.
     * Returns the transaction created by the Issuer to move the cash to the Requester.
     *
     * @param anonymous true if the issued asset should be sent to a new confidential identity, false to send it to the
     * well known identity (generally this is only used in testing).
     */
    @InitiatingFlow
    @StartableByRPC
    class IssuanceRequester(val amount: Amount<Currency>,
                            val issueToParty: Party,
                            val issueToPartyRef: OpaqueBytes,
                            val issuerBankParty: Party,
                            val notaryParty: Party,
                            val anonymous: Boolean) : FlowLogic<AbstractCashFlow.Result>() {
        @Suspendable
        @Throws(CashException::class)
        override fun call(): AbstractCashFlow.Result {
            val issueRequest = IssuanceRequestState(amount, issueToParty, issueToPartyRef, notaryParty, anonymous)
            return sendAndReceive<AbstractCashFlow.Result>(issuerBankParty, issueRequest).unwrap { moveTx ->
                require(anonymous == false || moveTx.identities.identities.isNotEmpty())
                val tx = moveTx.stx.tx
                val recipient = if (anonymous) {
                    moveTx.identities.forParty(issueToParty).identity
                } else {
                    issueToParty
                }
                val expectedAmount = Amount(amount.quantity, Issued(issuerBankParty.ref(issueToPartyRef), amount.token))
                val cashOutputs = tx.outputs
                        .map { it.data}
                        .filterIsInstance<Cash.State>()
                        .filter { state -> state.owner == recipient }
                require(cashOutputs.size == 1) { "Require a single cash output paying $recipient, found ${tx.outputs}" }
                require(cashOutputs.single().amount == expectedAmount) { "Require payment of $expectedAmount"}
                moveTx
            }
        }
    }

    /**
     * Issuer refers to a Node acting as a Bank Issuer of [FungibleAsset], and processes requests from a [IssuanceRequester] client.
     * Returns the generated transaction representing the transfer of the [Issued] [FungibleAsset] to the issue requester.
     */
    @InitiatedBy(IssuanceRequester::class)
    class Issuer(val otherParty: Party) : FlowLogic<SignedTransaction>() {
        companion object {
            object AWAITING_REQUEST : ProgressTracker.Step("Awaiting issuance request")
            object ISSUING : ProgressTracker.Step("Self issuing asset")
            object TRANSFERRING : ProgressTracker.Step("Transferring asset to issuance requester")
            object SENDING_CONFIRM : ProgressTracker.Step("Confirming asset issuance to requester")

            fun tracker() = ProgressTracker(AWAITING_REQUEST, ISSUING, TRANSFERRING, SENDING_CONFIRM)
            private val VALID_CURRENCIES = listOf(USD, GBP, EUR, CHF)
        }

        override val progressTracker: ProgressTracker = tracker()

        @Suspendable
        @Throws(CashException::class)
        override fun call(): SignedTransaction {
            progressTracker.currentStep = AWAITING_REQUEST
            val issueRequest = receive<IssuanceRequestState>(otherParty).unwrap {
                // validate request inputs (for example, lets restrict the types of currency that can be issued)
                if (it.amount.token !in VALID_CURRENCIES) throw FlowException("Currency must be one of $VALID_CURRENCIES")
                it
            }
            // TODO: parse request to determine Asset to issue
            val moveTx = issueCashTo(issueRequest.amount, issueRequest.issueToParty, issueRequest.issuerPartyRef, issueRequest.notaryParty, issueRequest.anonymous)
            require(issueRequest.anonymous == false || moveTx.identities.identities.isNotEmpty())
            progressTracker.currentStep = SENDING_CONFIRM
            send(otherParty, moveTx)
            return moveTx.stx
        }

        @Suspendable
        private fun issueCashTo(amount: Amount<Currency>,
                                issueTo: Party,
                                issuerPartyRef: OpaqueBytes,
                                notaryParty: Party,
                                anonymous: Boolean): AbstractCashFlow.Result {
            // invoke Cash subflow to issue Asset
            progressTracker.currentStep = ISSUING
            val issueRecipient = serviceHub.myInfo.legalIdentity
            val issueCashFlow = CashIssueFlow(amount, issuerPartyRef, issueRecipient, notaryParty, anonymous)
            val issueTx = subFlow(issueCashFlow)
            // NOTE: issueCashFlow performs a Broadcast (which stores a local copy of the txn to the ledger)
            // short-circuit when issuing to self
            if (issueTo == serviceHub.myInfo.legalIdentity)
                return issueTx
            // now invoke Cash subflow to Move issued assetType to issue requester
            progressTracker.currentStep = TRANSFERRING
            val moveCashFlow = CashPaymentFlow(amount, issueTo, anonymous)
            val moveTx: AbstractCashFlow.Result = subFlow(moveCashFlow)
            require(anonymous == false || moveTx.identities.identities.isNotEmpty())
            // NOTE: CashFlow PayCash calls FinalityFlow which performs a Broadcast (which stores a local copy of the txn to the ledger)
            return moveTx
        }
    }
}