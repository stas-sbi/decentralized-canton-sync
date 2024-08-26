package com.daml.network.util

import com.daml.network.integration.tests.FrontendTestCommon
import com.daml.network.util.WalletFrontendTestUtil.*
import com.digitalasset.canton.topology.PartyId
import org.scalatest.Assertion

import scala.concurrent.duration.*

trait WalletFrontendTestUtil extends WalletTestUtil { self: FrontendTestCommon =>

  protected def tapAmulets(tapQuantity: BigDecimal)(implicit webDriver: WebDriverType): Unit = {
    val txDatesBefore =
      clue("Getting state before tap") {
        // The long eventually makes this robust against `StaleElementReferenceException` errors
        eventually(timeUntilSuccess = 2.minute)(
          findAll(className("tx-row")).toSeq.map(readDateFromRow)
        )
      }

    logger.debug(s"Transaction dates before tap: $txDatesBefore")

    clue("Tapping...") {
      click on "tap-amount-field"
      numberField("tap-amount-field").underlying.clear()
      numberField("tap-amount-field").underlying.sendKeys(tapQuantity.toString())
      click on "tap-button"
    }

    clue("Making sure the tap has been processed") {
      // This will have to change if we add a reload button here instead of auto-refreshing transactions.
      // The long eventually makes this robust against `StaleElementReferenceException` errors
      eventually(timeUntilSuccess = 2.minute) {
        find(className(errorDisplayElementClass)).map { errElem =>
          (errElem.text.trim, find(className(errorDetailsElementClass)).map(_.text.trim))
        } shouldBe empty
        val txs = findAll(className("tx-row")).toSeq
        val txDatesAfter = txs.map(readDateFromRow)
        logger.debug(s"Transaction dates after tap: $txDatesAfter")
        val tapsAfter = txs.flatMap(readTapFromRow)
        val newTaps = tapsAfter.filter(tap => !txDatesBefore.exists(_ == tap.date))
        logger.debug(s"New taps: $newTaps")
        forAtLeast(1, newTaps) { tap =>
          tap.tapAmount shouldBe walletUsdToAmulet(tapQuantity)
        }
      }
    }
  }

  protected def matchBalance(balanceCC: String, balanceUSD: String)(implicit
      webDriverType: WebDriverType
  ): Assertion = {
    find(id("wallet-balance-cc"))
      .valueOrFail("Couldn't find balance")
      .text should matchText(s"$balanceCC CC")

    find(id("wallet-balance-usd"))
      .valueOrFail("Couldn't find balance")
      .text should matchText(s"$balanceUSD USD")
  }

  def parseAmountText(str: String, unit: String) = {
    try {
      BigDecimal(
        str
          .replace(unit, "")
          .trim
          .replace(",", "")
      )
    } catch {
      case e: Throwable =>
        throw new RuntimeException(s"Could not parse the string '$str' as a amulet amount", e)
    }
  }

  protected def readTransactionFromRow(transactionRow: Element): FrontendTransaction = {

    FrontendTransaction(
      action = transactionRow.childElement(className("tx-action")).text,
      subtype = transactionRow.childElement(className("tx-subtype")).text.replaceAll("[()]", ""),
      partyDescription = for {
        senderOrReceiver <- transactionRow
          .findChildElement(className("sender-or-receiver"))
          .map(seleniumText)
        providerId <- transactionRow.findChildElement(className("provider-id")).map(seleniumText)
      } yield s"${senderOrReceiver} ${providerId}",
      ccAmount = parseAmountText(
        transactionRow
          .childElement(className("tx-row-cell-balance-change"))
          .childElement(className("tx-amount-cc"))
          .text,
        unit = "CC",
      ),
      usdAmount = parseAmountText(
        transactionRow
          .childElement(className("tx-row-cell-balance-change"))
          .childElement(className("tx-amount-usd"))
          .text,
        unit = "USD",
      ),
      rate = transactionRow.childElement(className("tx-amount-rate")).text,
      appRewardsUsed = parseAmountText(
        transactionRow
          .childElement(className("tx-row-cell-rewards"))
          .findChildElement(className("tx-reward-app-cc"))
          .map(_.text)
          .getOrElse("0 CC"),
        unit = "CC",
      ),
      validatorRewardsUsed = parseAmountText(
        transactionRow
          .childElement(className("tx-row-cell-rewards"))
          .findChildElement(className("tx-reward-validator-cc"))
          .map(_.text)
          .getOrElse("0 CC"),
        unit = "CC",
      ),
      svRewardsUsed = parseAmountText(
        transactionRow
          .childElement(className("tx-row-cell-rewards"))
          .findChildElement(className("tx-reward-sv-cc"))
          .map(_.text)
          .getOrElse("0 CC"),
        unit = "CC",
      ),
    )
  }

  protected def matchTransaction(transactionRow: Element)(
      amuletPrice: BigDecimal,
      expectedAction: String,
      expectedSubtype: String,
      expectedPartyDescription: Option[String],
      expectedAmountAmulet: BigDecimal,
  ): Assertion = {
    val expectedUSD = expectedAmountAmulet * amuletPrice
    matchTransactionAmountRange(transactionRow)(
      amuletPrice,
      expectedAction,
      expectedSubtype,
      expectedPartyDescription,
      (expectedAmountAmulet - smallAmount, expectedAmountAmulet),
      (expectedUSD - smallAmount * amuletPrice, expectedUSD),
    )
  }

  protected def matchTransactionAmountRange(transactionRow: Element)(
      amuletPrice: BigDecimal,
      expectedAction: String,
      expectedSubtype: String,
      expectedPartyDescription: Option[String],
      expectedAmountAmulet: (BigDecimal, BigDecimal),
      expectedAmountUSD: (BigDecimal, BigDecimal),
  ): Assertion = {
    val transaction = readTransactionFromRow(transactionRow)

    transaction.action should matchText(expectedAction)
    transaction.subtype should matchText(expectedSubtype)
    (transaction.partyDescription, expectedPartyDescription) match {
      case (None, None) => ()
      case (Some(party), Some(ep)) => party should matchText(ep)
      case _ => fail(s"Unexpected party in transaction: $transaction")
    }
    transaction.ccAmount should beWithin(expectedAmountAmulet._1, expectedAmountAmulet._2)
    transaction.usdAmount should beWithin(
      expectedAmountUSD._1,
      expectedAmountUSD._2,
    )
    transaction.rate should matchText(s"${BigDecimal(1) / amuletPrice} CC/USD")
  }

  protected def createTransferOffer(
      receiver: PartyId,
      transferAmount: BigDecimal,
      expiryDays: Int,
      description: String = "by party ID",
  )(implicit
      driver: WebDriverType
  ) = {
    click on "navlink-transfer"
    click on "create-offer-receiver"
    setAnsField(
      textField("create-offer-receiver"),
      receiver.toProtoPrimitive,
      receiver.toProtoPrimitive,
    )

    click on "create-offer-cc-amount"
    numberField("create-offer-cc-amount").value = ""
    numberField("create-offer-cc-amount").underlying.sendKeys(transferAmount.toString())

    click on "create-offer-expiration-days"
    singleSel("create-offer-expiration-days").value = expiryDays.toString

    click on "create-offer-description"
    textArea("create-offer-description").underlying.sendKeys(description)

    click on "create-offer-submit-button"
  }

  private def readTapFromRow(transactionRow: Element): Option[Tap] = {
    val date = readDateFromRow(transactionRow)
    val amountO =
      if (
        transactionRow
          .childElement(className("tx-action"))
          .text
          .contains("Balance Change") && transactionRow
          .childElement(className("tx-subtype"))
          .text
          .contains("Tap")
      ) {
        Some(
          parseAmountText(
            transactionRow
              .childElement(className("tx-amount-cc"))
              .text,
            unit = "CC",
          )
        )
      } else None
    amountO.map(Tap(date, _))
  }

  private def readDateFromRow(transactionRow: Element): String =
    transactionRow
      .childElement(className("tx-row-cell-date"))
      .text
}

object WalletFrontendTestUtil {

  case class FrontendTransaction(
      action: String,
      subtype: String,
      partyDescription: Option[String],
      ccAmount: BigDecimal,
      usdAmount: BigDecimal,
      rate: String,
      appRewardsUsed: BigDecimal,
      validatorRewardsUsed: BigDecimal,
      svRewardsUsed: BigDecimal,
  )

  val errorDisplayElementClass = "error-display-message"
  val errorDetailsElementClass = "error-display-details"

  final case class Tap(
      date: String,
      tapAmount: BigDecimal,
  )
}
